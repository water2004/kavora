/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dbghelp.h>
#include <tlhelp32.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>

static const char* exceptionCodeName(DWORD code) {
  switch (code) {
      case 0xC0000005: return "EXCEPTION_ACCESS_VIOLATION";
      case 0xC00000FD: return "EXCEPTION_STACK_OVERFLOW";
      case 0xC000008C: return "EXCEPTION_ARRAY_BOUNDS_EXCEEDED";
      case 0xC000001D: return "EXCEPTION_ILLEGAL_INSTRUCTION";
      case 0xC0000094: return "EXCEPTION_INT_DIVIDE_BY_ZERO";
      case 0xC0000096: return "EXCEPTION_PRIV_INSTRUCTION";
      case 0xC0000006: return "EXCEPTION_IN_PAGE_ERROR";
      case 0x80000001: return "EXCEPTION_GUARD_PAGE";
      case 0xC0000374: return "STATUS_HEAP_CORRUPTION";
      case 0xC0000409: return "STATUS_STACK_BUFFER_OVERRUN (__fastfail)";
      default:         return "UNKNOWN";
  }
}

// Resolve an address to "module.dll+0xOFFSET" or "??? (JIT/heap)" if unmapped
static void formatAddress(FILE* f, uintptr_t addr) {
  HMODULE mod = nullptr;
  if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                         GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                         (LPCSTR)addr, &mod) && mod) {
      char name[MAX_PATH];
      if (GetModuleFileNameA(mod, name, sizeof(name))) {
          const char* base = strrchr(name, '\\');
          base = base ? base + 1 : name;
          fprintf(f, "%s+0x%llX", base, (unsigned long long)(addr - (uintptr_t)mod));
          return;
      }
  }
  fprintf(f, "0x%016llX (JIT/heap)", (unsigned long long)addr);
}

static void writeModuleList(FILE* f) {
  fprintf(f, "\n=== Loaded Modules ===\n");
  HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
  if (snap == INVALID_HANDLE_VALUE) return;
  MODULEENTRY32 me;
  me.dwSize = sizeof(me);
  if (Module32First(snap, &me)) {
      do {
          fprintf(f, "  %016llX - %016llX  %s\n",
                  (unsigned long long)(uintptr_t)me.modBaseAddr,
                  (unsigned long long)((uintptr_t)me.modBaseAddr + me.modBaseSize),
                  me.szModule);
      } while (Module32Next(snap, &me));
  }
  CloseHandle(snap);
}

static void writeRegisters(FILE* f, const CONTEXT* ctx) {
  fprintf(f, "\n=== Registers ===\n");
#if defined(_M_X64)
  fprintf(f, "  RAX=%016llX  RBX=%016llX  RCX=%016llX  RDX=%016llX\n",
          ctx->Rax, ctx->Rbx, ctx->Rcx, ctx->Rdx);
  fprintf(f, "  RSI=%016llX  RDI=%016llX  RSP=%016llX  RBP=%016llX\n",
          ctx->Rsi, ctx->Rdi, ctx->Rsp, ctx->Rbp);
  fprintf(f, "  R8 =%016llX  R9 =%016llX  R10=%016llX  R11=%016llX\n",
          ctx->R8, ctx->R9, ctx->R10, ctx->R11);
  fprintf(f, "  R12=%016llX  R13=%016llX  R14=%016llX  R15=%016llX\n",
          ctx->R12, ctx->R13, ctx->R14, ctx->R15);
  fprintf(f, "  RIP=%016llX  EFLAGS=%08lX\n", ctx->Rip, ctx->EFlags);
#elif defined(_M_ARM64)
  for (int i = 0; i < 28; i += 4)
      fprintf(f, "  X%-2d=%016llX  X%-2d=%016llX  X%-2d=%016llX  X%-2d=%016llX\n",
              i, ctx->X[i], i+1, ctx->X[i+1], i+2, ctx->X[i+2], i+3, ctx->X[i+3]);
  fprintf(f, "  X28=%016llX\n", ctx->X[28]);
  fprintf(f, "  FP =%016llX  LR =%016llX  SP =%016llX  PC =%016llX\n",
          ctx->Fp, ctx->Lr, ctx->Sp, ctx->Pc);
  fprintf(f, "  CPSR=%08lX\n", ctx->Cpsr);
#endif
}

static void walkStack(FILE* f, HANDLE process, HANDLE thread, CONTEXT ctx) {
  STACKFRAME64 frame = {};
#if defined(_M_X64)
  DWORD machineType     = IMAGE_FILE_MACHINE_AMD64;
  frame.AddrPC.Offset    = ctx.Rip;
  frame.AddrFrame.Offset = ctx.Rbp;
  frame.AddrStack.Offset = ctx.Rsp;
#elif defined(_M_ARM64)
  DWORD machineType     = IMAGE_FILE_MACHINE_ARM64;
  frame.AddrPC.Offset    = ctx.Pc;
  frame.AddrFrame.Offset = ctx.Fp;
  frame.AddrStack.Offset = ctx.Sp;
#endif
  frame.AddrPC.Mode      = AddrModeFlat;
  frame.AddrFrame.Mode   = AddrModeFlat;
  frame.AddrStack.Mode   = AddrModeFlat;

  for (int i = 0; i < 64; i++) {
      if (!StackWalk64(machineType, process, thread,
                       &frame, &ctx, nullptr,
                       SymFunctionTableAccess64, SymGetModuleBase64, nullptr))
          break;
      if (frame.AddrPC.Offset == 0) break;

      fprintf(f, "    [%2d] ", i);
      formatAddress(f, (uintptr_t)frame.AddrPC.Offset);
      fprintf(f, "\n");
  }
}

static void writeAllThreadStacks(FILE* f, DWORD faultingTid, EXCEPTION_POINTERS* ep) {
  HANDLE process = GetCurrentProcess();
  SymInitialize(process, nullptr, TRUE);

  // Faulting thread first: use the exception context directly
  fprintf(f, "\n=== Faulting Thread (TID %lu) ===\n", faultingTid);
  walkStack(f, process, GetCurrentThread(), *ep->ContextRecord);

  fprintf(f, "\n=== Other Threads ===\n");
  HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
  if (snap != INVALID_HANDLE_VALUE) {
      THREADENTRY32 te;
      te.dwSize = sizeof(te);
      DWORD pid = GetCurrentProcessId();
      if (Thread32First(snap, &te)) {
          do {
              if (te.th32OwnerProcessID != pid) continue;
              if (te.th32ThreadID == faultingTid) continue;

              HANDLE th = OpenThread(THREAD_GET_CONTEXT | THREAD_SUSPEND_RESUME |
                                     THREAD_QUERY_INFORMATION, FALSE, te.th32ThreadID);
              if (!th) continue;

              SuspendThread(th);
              CONTEXT ctx = {};
              ctx.ContextFlags = CONTEXT_FULL;
              if (GetThreadContext(th, &ctx)) {
                  fprintf(f, "\n  Thread %lu:\n", te.th32ThreadID);
                  walkStack(f, process, th, ctx);
              }
              ResumeThread(th);
              CloseHandle(th);
          } while (Thread32Next(snap, &te));
      }
      CloseHandle(snap);
  }

  SymCleanup(process);
}

// Writes both a .dmp and a .txt crash report for the given exception.
static void writeCrashFiles(EXCEPTION_POINTERS* ep) {
  static volatile LONG dumping = 0;
  if (InterlockedCompareExchange(&dumping, 1, 0) != 0)
      return;

  CreateDirectoryA("crash", nullptr);

  char tag[32];
  time_t now = time(nullptr);
  struct tm tm;
  localtime_s(&tm, &now);
  snprintf(tag, sizeof(tag), "%04d%02d%02d_%02d%02d%02d",
           tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
           tm.tm_hour, tm.tm_min, tm.tm_sec);

  // ── Minidump ──
  char dmpPath[MAX_PATH];
  snprintf(dmpPath, sizeof(dmpPath), "crash\\minidump_%s.dmp", tag);

  HANDLE dmpFile = CreateFileA(dmpPath, GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (dmpFile != INVALID_HANDLE_VALUE) {
      MINIDUMP_EXCEPTION_INFORMATION mei;
      mei.ThreadId          = GetCurrentThreadId();
      mei.ExceptionPointers = ep;
      mei.ClientPointers    = FALSE;

      DWORD flags = MiniDumpWithDataSegs
                  | MiniDumpWithHandleData
                  | MiniDumpWithThreadInfo
                  | MiniDumpWithUnloadedModules
                  | MiniDumpWithFullMemoryInfo
                  | MiniDumpWithIndirectlyReferencedMemory;

      MiniDumpWriteDump(GetCurrentProcess(), GetCurrentProcessId(),
                        dmpFile, (MINIDUMP_TYPE)flags, &mei, nullptr, nullptr);
      CloseHandle(dmpFile);
  }

  // ── Text crash report ──
  char txtPath[MAX_PATH];
  snprintf(txtPath, sizeof(txtPath), "crash\\crash_report_%s.txt", tag);

  DWORD code = ep->ExceptionRecord->ExceptionCode;

  FILE* f = fopen(txtPath, "w");
  if (f) {
      fprintf(f, "=== RTX Remix for Minecraft: Crash Report ===\n");
      fprintf(f, "Minidump: %s\n\n", dmpPath);

      fprintf(f, "Exception: 0x%08lX (%s)\n",
              code, exceptionCodeName(code));
      fprintf(f, "Address:   ");
      formatAddress(f, (uintptr_t)ep->ExceptionRecord->ExceptionAddress);
      fprintf(f, "\n");

      if (code == EXCEPTION_ACCESS_VIOLATION && ep->ExceptionRecord->NumberParameters >= 2) {
          const char* op = ep->ExceptionRecord->ExceptionInformation[0] == 0 ? "reading"
                         : ep->ExceptionRecord->ExceptionInformation[0] == 1 ? "writing"
                         : "executing";
          fprintf(f, "Fault:     %s 0x%016llX\n",
                  op, (unsigned long long)ep->ExceptionRecord->ExceptionInformation[1]);
      }

      fprintf(f, "Thread:    %lu\n", GetCurrentThreadId());
      fprintf(f, "Process:   %lu\n", GetCurrentProcessId());

      writeRegisters(f, ep->ContextRecord);
      writeAllThreadStacks(f, GetCurrentThreadId(), ep);
      writeModuleList(f);

      fclose(f);
  }
}

// Unhandled Exception Filter: only fires when NO handler (including the
// JVM's own VEH for safepoint polls, implicit null checks, and stack
// overflow detection) could handle the exception. This is a genuine crash.
static LPTOP_LEVEL_EXCEPTION_FILTER g_prevFilter = nullptr;

static LONG WINAPI crashExceptionFilter(EXCEPTION_POINTERS* ep) {
  writeCrashFiles(ep);

  if (g_prevFilter)
      return g_prevFilter(ep);
  return EXCEPTION_CONTINUE_SEARCH;
}

static bool g_installed = false;
static std::mutex g_handlerMutex;
extern "C" __declspec(dllexport) int32_t rtxremix_install_crash_handler() {
  std::lock_guard lock(g_handlerMutex);
  if (!g_installed) {
      g_prevFilter = SetUnhandledExceptionFilter(crashExceptionFilter);
      g_installed = true;
  }
  return 1;
}

extern "C" __declspec(dllexport) void rtxremix_uninstall_crash_handler() {
  std::lock_guard lock(g_handlerMutex);
  if (g_installed) {
      SetUnhandledExceptionFilter(g_prevFilter);
      g_prevFilter = nullptr;
      g_installed = false;
  }
}

