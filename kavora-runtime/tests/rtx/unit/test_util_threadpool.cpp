/*
* Copyright (c) 2023, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
#include <cstring>
#include <random>
#include <chrono>
#include <iostream>
#include <cstdio>
#include <cstdlib>
#include <utility>

#include "../../test_utils.h"
#include "../../../src/util/util_threadpool.h"
#include "../../../src/util/util_timer.h"
#include "../../../src/tracy/Tracy.hpp"

namespace dxvk {
  // Note: Logger needed by some shared code used in this Unit Test.
  Logger Logger::s_instance("test_util_threadpool.log");
}

using namespace dxvk;
using namespace std;
using namespace chrono;

inline uint64_t hwCounter() {
#if _M_ARM64
  return _ReadStatusReg(ARM64_SYSREG(3,3, 9,13,0));
#else
  return __rdtsc();
#endif
}

class ThreadPoolTestApp {
public:
  static void run() { 
    cout << "Begin smoke test" << endl;
    test_smoke<0>();
    cout << "Begin task cancellation test" << endl;
    test_smoke<4>();
    cout << "Begin misc tests" << endl;
    test_misc();
    cout << "Begin sleeping worker wakeup test" << endl;
    test_sleepingWorkers();
    cout << "WorkerThreadPool successfully smoke tested" << endl;
  }
  
private:
  static void test_unconsumedResults() {
    WorkerThreadPool<2, true, false, true> pool(1, "result-slot-regression");
    std::atomic<bool> secondStarted = false;
    auto first = pool.Schedule([] { return uint32_t(101); });
    Future<uint32_t> second;
    do {
      second = pool.Schedule([&] {
        secondStarted = true;
        return uint32_t(202);
      });
      if (!second.valid()) {
        std::this_thread::yield();
      }
    } while (!second.valid());
    while (!secondStarted) {
      std::this_thread::yield();
    }
    // The first task has completed and left the queue, but its Future still owns
    // the ring slot. Queue capacity alone must not permit overwriting its result.
    auto rejected = pool.Schedule([] { return uint32_t(999); });
    if (rejected.valid()) {
      throw DxvkError("Task ring reused an unconsumed Future result");
    }
    if (!first.valid() || !second.valid() || first.get() != 101 || second.get() != 202) {
      throw DxvkError("Task ring overwrote the original Future values");
    }
    Future<uint32_t> retry;
    do {
      retry = pool.Schedule([] { return uint32_t(303); });
      if (!retry.valid()) {
        std::this_thread::yield();
      }
    } while (!retry.valid());
    if (retry.get() != 303) {
      throw DxvkError("Consumed task ring slots did not accept a retried Schedule");
    }
  }

  static void test_movingResult() {
    struct MovingResult {
      uint32_t value;
      std::atomic<bool>* pause;
      std::atomic<bool>* moving;
      std::atomic<bool>* resume;

      MovingResult(uint32_t value, std::atomic<bool>& pause, std::atomic<bool>& moving, std::atomic<bool>& resume)
        : value(value), pause(&pause), moving(&moving), resume(&resume) { }

      MovingResult(MovingResult&& other)
        : value(other.value), pause(other.pause), moving(other.moving), resume(other.resume) {
        if (*pause) {
          *moving = true;
          while (!*resume) {
            std::this_thread::yield();
          }
          value = other.value;
        }
      }
    };
    WorkerThreadPool<2, true, false, true> pool(1, "moving-result-regression");
    std::atomic<bool> pause = false;
    std::atomic<bool> moving = false;
    std::atomic<bool> resume = false;
    auto first = pool.Schedule([&] { return MovingResult(404, pause, moving, resume); });
    Future<uint32_t> second;
    do {
      second = pool.Schedule([] { return uint32_t(505); });
      if (!second.valid()) {
        std::this_thread::yield();
      }
    } while (!second.valid());
    if (!first.valid() || !second.valid() || second.get() != 505) {
      throw DxvkError("Unable to prepare the moving-result ring regression");
    }
    pause = true;
    uint32_t value = 0;
    std::thread consumer([&] { value = first.get().value; });
    while (!moving) {
      std::this_thread::yield();
    }
    auto rejected = pool.Schedule([&] { return MovingResult(999, pause, moving, resume); });
    bool slotReused = rejected.valid();
    resume = true;
    consumer.join();
    if (slotReused || value != 404) {
      throw DxvkError("Task result slot became reusable before its value finished moving");
    }
  }

  static void test_callableCleanup() {
    if (dxvk::thread::hardware_concurrency() < 2) {
      return;
    }
    struct BlockingTask {
      std::atomic<bool>* started;
      std::atomic<bool>* resume;
      bool active = true;

      BlockingTask(std::atomic<bool>& started, std::atomic<bool>& resume)
        : started(&started), resume(&resume) { }
      BlockingTask(BlockingTask&& other)
        : started(other.started), resume(other.resume), active(std::exchange(other.active, false)) { }
      uint32_t operator()() const { return 606; }
      ~BlockingTask() {
        if (active) {
          auto pStarted = started;
          auto pResume = resume;
          *pStarted = true;
          while (!*pResume) {
            std::this_thread::yield();
          }
        }
      }
    };
    // Use separate fixed-affinity workers so one can make ring progress while
    // the other's callable destructor still owns its original storage.
    WorkerThreadPool<2, false, false> pool(2, "callable-cleanup-regression");
    std::atomic<bool> cleanupStarted = false;
    std::atomic<bool> resumeCleanup = false;
    auto first = pool.Schedule<0x01>(BlockingTask(cleanupStarted, resumeCleanup));
    if (!first.valid() || first.get() != 606) {
      throw DxvkError("Unable to prepare callable cleanup regression");
    }
    while (!cleanupStarted) {
      std::this_thread::yield();
    }
    for (uint32_t i = 0; i < 3; i++) {
      Future<uint32_t> progress;
      do {
        progress = pool.Schedule<0x02>([i] { return i + 707; });
        if (!progress.valid()) {
          std::this_thread::yield();
        }
      } while (!progress.valid());
      if (progress.get() != i + 707) {
        throw DxvkError("Independent worker lost its task result");
      }
    }
    auto rejected = pool.Schedule<0x02>([] { return uint32_t(999); });
    if (rejected.valid()) {
      fprintf(stderr, "Task ring overwrote a callable before its destructor completed\n");
      fflush(stderr);
      std::_Exit(EXIT_FAILURE);
    }
    resumeCleanup = true;
  }

  static void test_sleepingWorkers() {
    std::mutex watchdogMutex;
    std::condition_variable watchdogCondition;
    std::atomic<uint32_t> completed = 0;
    bool finished = false;
    std::thread watchdog([&] {
      std::unique_lock<std::mutex> lock(watchdogMutex);
      uint32_t observed = completed;
      while (!finished) {
        if (!watchdogCondition.wait_for(lock, seconds(5), [&] { return finished || completed != observed; })) {
          fprintf(stderr, "Sleeping WorkerThreadPool stalled after %u single-task wakeups\n", observed);
          fflush(stderr);
          // A lost notification blocks Future::get and pool destruction. Exit the
          // test process so its failure is bounded instead of hanging the suite.
          std::_Exit(EXIT_FAILURE);
        }
        observed = completed;
      }
    });
    auto finishWatchdog = [&] {
      {
        std::lock_guard<std::mutex> lock(watchdogMutex);
        finished = true;
      }
      watchdogCondition.notify_one();
      watchdog.join();
    };
    try {
      cout << "Begin retained task result test" << endl;
      test_unconsumedResults();
      cout << "Begin result move ownership test" << endl;
      test_movingResult();
      cout << "Begin callable cleanup ownership test" << endl;
      test_callableCleanup();
      cout << "Begin idle worker single-task cycles" << endl;
      for (uint8_t workers : { uint8_t(1), uint8_t(4) }) {
        WorkerThreadPool<32, true, false> sleepingPool(workers, "sleeping-worker-regression");
        for (uint32_t cycle = 0; cycle < 256; cycle++) {
          // Leave every worker with an empty queue before publishing exactly one
          // task, so completion cannot rely on another Schedule notification.
          std::this_thread::sleep_for(milliseconds(1));
          auto result = sleepingPool.Schedule([cycle] { return cycle + 1; });
          if (!result.valid() || result.get() != cycle + 1) {
            throw DxvkError("Sleeping worker returned an invalid or stale task result");
          }
          ++completed;
          watchdogCondition.notify_one();
        }
      }
    } catch (...) {
      finishWatchdog();
      throw;
    }
    finishWatchdog();
    if (completed != 512) {
      throw DxvkError("Sleeping worker wakeup cycle count did not match");
    }
  }

  template<uint32_t cancelPeriod>
  static void test_smoke() {
    ZoneScoped;
    // We'll need to size the pool according to the number of tasks
    //  here since its a ring buffer and this is a single thread...
    const uint32_t numThreads = 8;
    const uint32_t numTasks = 2000;

    WorkerThreadPool<numTasks> threadPool(numThreads);
    cout << "Created thread pool with " << numThreads << " threads" << endl;

    // Create params for lambda ahead of schedule
    random_device rd;
    mt19937 rng(rd());
    uniform_int_distribution<uint32_t> uni(0, 100);
    uint32_t a[numTasks], b[numTasks], c[numTasks], d[numTasks];

    for (uint32_t i = 0; i < numTasks; i++) {
      a[i] = uni(rng);
      b[i] = uni(rng);
      c[i] = uni(rng);
      d[i] = uni(rng);
    }

    vector<Future<uint32_t>> results(numTasks);
    {
      Timer t;
      const uint64_t s = hwCounter();

      for (uint32_t i = 0; i < numTasks; i++) {
        // Spawn N tasks that sleep a bit, and return a 1
        auto future = threadPool.Schedule([a = a[i], b = b[i], c = c[i], d = d[i]]()->uint32_t {
          ZoneScoped;
          // Create some varying workloads 
          double sleepFor = (double) (a + b + c + d) / 4;
          auto start = high_resolution_clock::now();
          while (duration_cast<milliseconds>(high_resolution_clock::now() - start).count() < sleepFor);

          return 1;
        });

        if (!future.valid()) {
          throw DxvkError("Failed to schedule task");
        }

        results[i] = future;

        if (cancelPeriod > 0 && (i % cancelPeriod) == 0) {
          future.cancel();
        }
      }

      const uint64_t e = hwCounter();

      cout << "Scheduled " << numTasks << " tasks in " << e - s << " clocks" << endl;
    }

    // Count all the return values (1's) and make sure everyone made it home
    uint32_t resultCount = 0;
    for (Future<uint32_t>& result : results) {
      if (result.valid()) {
        resultCount += result.get();
      }
    }

    const uint32_t expectedNumTasks = cancelPeriod > 0 ? (numTasks - numTasks / cancelPeriod) : numTasks;

    if (resultCount != expectedNumTasks) {
      throw DxvkError("Results didnt match");
    }

    // Check for ABA problem by making sure the correct count returned
    cout << "Counted the result, expected:" << expectedNumTasks << ", got:" << resultCount << endl;

    FrameMark;
  }

  static void test_misc() {
    const uint32_t numThreads = 4;
    const uint32_t numTasks = 32;

    auto* threadPool = new WorkerThreadPool<numTasks>(numThreads);
    cout << "Created thread pool with " << numThreads << " threads" << endl;

    uint32_t result = 0;
    auto future = threadPool->Schedule([&result]() {
      cout << "Hello from a void() future!" << endl;
      result += 1;
    });

    if (!future.valid()) {
      throw DxvkError("Failed to schedule task");
    }

    future.get();

    if (result != 1) {
      throw DxvkError("Result didnt match");
    }

    class DestuctorTester {
      // Using unique ptr to emulate a destructive move
      std::unique_ptr<uint32_t> param;
    public:
      DestuctorTester(DestuctorTester&& v) = default;
      DestuctorTester& operator =(DestuctorTester&&) = default;

      explicit DestuctorTester(uint32_t& param)
      : param { &param }
      { }

      ~DestuctorTester() {
        if (auto v = param.get()) {
          ++(*v);
          cout << "Hello from task destructor!" << endl;
          param.release();
        }
      }
    };

    DestuctorTester tester(result);
    future = threadPool->Schedule([tester = std::move(tester)]() {});

    if (!future.valid()) {
      throw DxvkError("Failed to schedule task");
    }

    // The lambda destructor is executed _after_ the future result is set.
    // We need to either wait for the result to update, or finalize the thread pool.
    delete threadPool;

    if (result != 2) {
      throw DxvkError("Result didnt match");
    }
  }
};

int main() {
  try {
    ThreadPoolTestApp::run();
  }
  catch (const dxvk::DxvkError& e) {
    cerr << e.message() << endl;
    return -1;
  }

  return 0;
}

