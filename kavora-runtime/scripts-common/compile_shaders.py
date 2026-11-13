
import argparse
import multiprocessing
import os
import re
import signal
import string
import subprocess
import sys
import time
import threading
import ctypes
import depfile
from shader_dependencies import ShaderDependencyCache
from shader_generation import publish_shader_generation, shader_generation_include
from shader_embedding import deduplicate_shaders, restore_inline_shaders
from slang_spirv_layout import LAYOUT_FLAGS

report_lock = threading.Lock()
task_lock = threading.Lock()
terminate = False

def sigint_handler(signal, frame):
    global terminate
    terminate = True

signal.signal(signal.SIGINT, sigint_handler)

parser = argparse.ArgumentParser(description='Compiles DXVK-RT shaders.')
parser.add_argument('-glslang', required=True, type=str, dest='glslang')
parser.add_argument('-slangc', required=True, type=str, dest='slangc')
parser.add_argument('-spirvval', required=True, type=str, dest='spirvval')
parser.add_argument('-input', required=False, type=str, dest='input', default='.')
parser.add_argument('-I', '-include', action='append', type=str, dest='includes', default=[])
parser.add_argument('-output', required=True, type=str, dest='output')
parser.add_argument('-force', action='store_true', dest='force')
parser.add_argument('-parallel', action='store_true', dest='parallel')
parser.add_argument('-binary', action='store_true', dest='binary')
parser.add_argument('-debug', action='store_true', dest='debug')
parser.add_argument('-slang-debug-variant', action='append', dest='slang_debug_variants',
                    default=[name.strip() for name in os.environ.get('RTX_SLANG_DEBUG_VARIANTS', '').split(',') if name.strip()],
                    help='Embed optimized Slang source debug information for this exact variant name; may be repeated.')
parser.add_argument('-build-stamp', type=str, default=None, dest='build_stamp')
parser.add_argument('-shared-header', type=str, default=None, dest='shared_header')
parser.add_argument('-shared-sources', nargs='*', default=[], dest='shared_sources')
args = parser.parse_args()

# Set to True to generate Slang repro file when compiling shaders
generateSlangRepro = False

includePaths = [f'-I{path}' for path in args.includes]
slangDll = os.path.join(os.path.dirname(args.slangc), 'slang-compiler.dll')

tools = [args.glslang, args.slangc, slangDll, __file__, depfile.__file__,
         os.path.join(os.path.dirname(__file__), 'shader_dependencies.py'),
         os.path.join(os.path.dirname(__file__), 'slang_spirv_layout.py')]

# Note: -Os (Optimize Size) used here as while one might typically expect optimizing for size to comprimise speed optimizations,
# the glslang optimizer actually just enables more optimizations when this option is specified, meaning it is probably good to enable
# always (assuming that data wouldn't help the actual driver compiler at least, and we've observed it to make a slight speedup overall):
# https://github.com/KhronosGroup/glslang/blob/master/SPIRV/SpvTools.cpp#L213
glslangFlags = ['--quiet', '--target-env', 'vulkan1.2', '-Os']

# Note: Debug is used for Debug and DebugOptimized currently, so it does not disable optimizations persay
# (as otherwise -Od should be passed and be mutually exclusive with -Os), just means to generate debug info.
if args.debug:
    glslangFlags.append('-g')

os.makedirs(args.output, exist_ok = True)
dependencyCache = ShaderDependencyCache(os.path.join(args.output, '.shader-inputs.json'), tools)


def printFromThread(what):
    report_lock.acquire()
    print(what)
    report_lock.release()


class Task:
    outputs = []
    inputs = []
    commands = []
    customName = None

    def needsBuild(self):
        if args.force:
            return True

        for output in self.outputs:
            if not os.path.exists(output):
                return True
        if not self.inputs:
            return True
        return dependencyCache.needs_build(self.outputs[0], self.inputs, self.commands)

    def build(self):
        allCommandOutputs = ''
        commandName = ''

        for command in self.commands:
            #print(command)
            timeStart = time.time()

            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            out, err = process.communicate()

            duration = time.time() - timeStart

            commandName = os.path.basename(command[0])
            printFromThread(f'[{duration:5.2f}s] {commandName}: {self.getName()}')

            combinedOutput = (out + err).decode("utf-8").strip()
            if len(combinedOutput):
                if len(allCommandOutputs):
                    allCommandOutputs += '\n'
                allCommandOutputs += combinedOutput

            if process.returncode != 0:
                # Convert the process exit code from uint32 to int32
                exitCode = ctypes.c_long(process.returncode).value
                return (exitCode, allCommandOutputs, commandName)

        with open(self.depFile, 'r') as dependencyFile:
            inputs = depfile.parse(dependencyFile.readlines(), self.depTarget)
        if not inputs:
            raise ValueError('Compiler produced no dependencies for ' + self.getName())
        dependencyCache.record_success(self.outputs[0], inputs + self.extraInputs, self.commands)
        return (0, allCommandOutputs, commandName)


    def getName(self):
        if self.customName is not None:
            return self.customName
        if len(self.inputs):
            return os.path.basename(self.inputs[0])
        if len(self.outputs):
            return os.path.basename(self.outputs[0])
        return "<UnknownTask>"

def runTasks(tasks):
    global terminate
    while True:
        if terminate:
            break

        task = None

        task_lock.acquire()
        tasksLeft = len(tasks)
        if tasksLeft > 0:
            task = tasks[0]
            del tasks[0]
        task_lock.release()

        if task is None:
            break
            
        # Workaround for occasional crashes of slangc.exe on the build farm
        # Not necessary anymore
        maxAttempts = 1
        for attempt in range(maxAttempts):
            try:
                exitCode, output, lastCommand = task.build()
            except:
                terminate = True
                raise

            if len(output):
                printFromThread(f'\n{lastCommand} output for {task.getName()}:\n{output}')

            elif exitCode != 0:
                printFromThread(f'\n{lastCommand} exited with code {exitCode} and no output for {task.getName()}, possibly crashed (attempt {attempt+1}/{maxAttempts}).')

                if (exitCode == 1) and (attempt < maxAttempts - 1):
                    continue
            break

        if exitCode != 0:
            terminate = True

def getShaderName(inputFile):
    return os.path.splitext(os.path.basename(inputFile))[0]


def createBasicTask(inputFile, destFile, targetName, depFile):
    task = Task()
    task.depFile = depFile
    task.depTarget = targetName
    task.extraInputs = []
    try:
        lines = open(depFile, 'r').readlines()
        task.inputs = depfile.parse(lines, targetName)
    except:
        task.inputs = []
    task.outputs = [destFile, depFile]
    return task

def createGlslangTask(inputFile):
    shaderName = getShaderName(inputFile)
    destExtension = '.spv' if args.binary else '.h'
    destFile = os.path.join(args.output, shaderName + destExtension)
    depFile = os.path.join(args.output, shaderName + ".d")
    task = createBasicTask(inputFile, destFile, destFile, depFile)
    variableName = [] if args.binary else ['--vn', shaderName]

    command = [args.glslang, *glslangFlags, *includePaths, '-V', *variableName, '-o', destFile,
               '--depfile', depFile, inputFile]
    task.commands = [command]
    return task

def createSlangTask(inputFile, variantSpec):
    # Ensure slang runs validation
    os.environ['SLANG_RUN_SPIRV_VALIDATION'] = '1'

    inputName, inputType = os.path.splitext(getShaderName(inputFile))
    variantName, variantType = os.path.splitext(variantSpec[0])

    variantDefines = [f'-D{define}' for define in variantSpec[1:]]
    destFile = os.path.join(args.output, variantName + ".spv")
    headerFile = os.path.join(args.output, variantName + ".h")
    depFile = os.path.join(args.output, variantName + ".d")

    # Create task to resolve dep file for the compiler output (.spv)
    task = createBasicTask(inputFile, destFile, destFile, depFile)

    if variantName != inputName:
        task.customName = f'{os.path.basename(inputFile)} ({variantName})'

    command1 = [args.slangc, '-entry', 'main', '-target', 'spirv', '-zero-initialize',
                '-emit-spirv-directly', '-verbose-paths', *includePaths, '-depfile', depFile,
                inputFile, *variantDefines, *LAYOUT_FLAGS, '-Wno-30081']

    # Add SER capability only for variants that use Shader Execution Reordering
    if 'RT_SHADER_EXECUTION_REORDERING' in variantSpec:
        command1.extend(['-capability', 'spvShaderInvocationReorderNV'])

    if args.debug:
        command1.append('-g2')
    elif variantName in getattr(args, 'slang_debug_variants', []):
        command1.extend(['-g1', '-debug-info-include-source'])

    if generateSlangRepro:
        reproFile = os.path.join(args.output, variantName + ".slangRepro")
        command1.extend(['-dump-repro', reproFile])

    command1.extend(['-o', destFile])

    # -binary switch just writes the SPV binary
    if args.binary:
        task.commands = [command1]
    else:
        # NOTE: rebuild shader if its generated header is missing
        task.outputs.append(headerFile)

        # Command to convert SPV into c array header
        script_dir = os.path.dirname(os.path.realpath(__file__))
        shader_xxd = os.path.join(script_dir, 'shader_xxd.py')
        task.inputs.append(shader_xxd)
        task.extraInputs.append(shader_xxd)
        command2 = [sys.executable, shader_xxd, '-i', destFile, '-o', headerFile]

        task.commands = [command1, command2]

    return task

# Read the shader variant specifications from the source code.
# The specifications must follow this pattern:
#    //!variant <name1> <defines...>
#    //!>       <defines-continued...>
# The defines can be all specified on the first line or split into multiple lines.
# Defines with values are supported, just use the NAME=VALUE syntax.
# After all variants are declared, a closing statement must be used:
#    //!end-variants
#
# The function returns a list of variantSpec items, where each item is [name, define1, define2...]
# An empty result means there was an error parsing the specifications.
#
# For example, if a shader my_shader.slang has these two variants:
#    //!variant my_shader_a.comp MY_CONST=0
#    //!variant my_shader_b.rgen MY_CONST=1
#    //!end-variants
# Then two outputs will be produced after the script finishes:
#    my_shader_a.comp -> my_shader_a.h with `const uint32_t my_shader_a[]`
#    my_shader_b.rgen -> my_shader_b.h with `const uint32_t my_shader_b[]`
#
# Matrix declarations are also supported for passes with cross-product variants.
# Use a matrix when a shader has independent feature dimensions and spelling out
# every `//!variant` line would be noisy or easy to desynchronize from C++.
#
# The matrix starts with `//!variant-matrix <base-name>`. The base name is the
# prefix used for generated shader names and for the companion
# `<base-name>_variants.h` header.
#
# `//!> common <defines...>` appends defines to every generated variant. Common
# lines are useful for shader-wide mode defines that should not affect naming.
#
# `//!> axis <axis-name> <suffix> <defines...>` declares one selectable value on
# an axis. Every axis value is combined with every value from the other axes.
# The axis name is used only for generated constants and X-macro parameters.
# The suffix contributes to the generated shader name, and may also override the
# shader stage by including a shader extension such as `.comp`, `.rgen`, or
# `.rchit`.
#
# A suffix of `-` means "no name suffix and no shader stage override". This is
# typically used for the default value of an axis, for example a feature-off
# value that should keep the shorter base shader name.
#
# `//!> order <axis-name...>` fixes the order used for generated names, X-macro
# parameters, and the emitted axis constants. If omitted, axes are emitted in
# declaration order.
#
# Matrix variants can still use `//!>` continuation lines. A continuation line
# extends the previous `common` or `axis` declaration with more defines.
#
# Each generated variant receives all common defines plus the defines from one
# value on each axis. If no axis suffix gives a shader extension, the input
# file extension is used as the shader stage.
#    //!variant-matrix my_shader
#    //!> common COMMON_DEFINE
#    //!> axis mode rayquery.comp USE_RAYQUERY
#    //!> axis mode raygen.rgen RAY_PIPELINE
#    //!> axis feature -
#    //!> axis feature debug ENABLE_DEBUG=1
#    //!> order mode feature
#    //!end-variants
# This expands to:
#    my_shader_rayquery.comp COMMON_DEFINE USE_RAYQUERY
#    my_shader_rayquery_debug.comp COMMON_DEFINE USE_RAYQUERY ENABLE_DEBUG=1
#    my_shader_raygen.rgen COMMON_DEFINE RAY_PIPELINE
#    my_shader_raygen_debug.rgen COMMON_DEFINE RAY_PIPELINE ENABLE_DEBUG=1
# It also emits my_shader_variants.h with axis constants and filtered X-macros
# for C++ selection code.
shaderTypeSuffixes = {
    ".comp", ".vert", ".geom", ".frag", ".rgen", ".rchit", ".rahit", ".rmiss", ".rint", ".rcall"
}

def getVariantMatrixToken(value):
    token = re.sub("[^a-zA-Z0-9]+", "_", value).strip("_")
    if len(token) == 0:
        token = "none"
    return token.upper()

def getVariantMatrixSuffixNameAndType(inputFile, lineno, suffix):
    if suffix == "-":
        return "", ""

    suffixName, suffixType = os.path.splitext(suffix)
    if suffixType != "" and suffixType not in shaderTypeSuffixes:
        print(f'{inputFile}:{lineno}: invalid shader type "{suffixType}" in variant matrix suffix "{suffix}"')
        return None, None

    return suffixName, suffixType

def writeVariantMatrixHeader(inputFile, matrix, axisOrder, variants):
    if args.binary:
        return

    baseToken = getVariantMatrixToken(matrix["base"])
    destFile = os.path.join(args.output, matrix["base"] + "_variants.h")
    lines = [
        "#pragma once",
        "",
        f"// Generated by compile_shaders.py from {os.path.basename(inputFile)}.",
        "",
    ]

    for axisName in axisOrder:
        axisToken = getVariantMatrixToken(axisName)
        for value in matrix["axes"][axisName]:
            valueToken = getVariantMatrixToken(value["label"])
            lines.append(f"#define RTX_SHADER_VARIANT_MATRIX_{baseToken}_{axisToken}_{valueToken} {value['index']}")
        lines.append("")

    for variant in variants:
        lines.append(f"#include <rtx_shaders/{variant['name']}.h>")
    lines.append("")

    variantTypes = sorted(set([variant["type"] for variant in variants]))

    for axisName in axisOrder:
        axisToken = getVariantMatrixToken(axisName)
        axisIndex = axisOrder.index(axisName)

        for value in matrix["axes"][axisName]:
            valueToken = getVariantMatrixToken(value["label"])
            for variantType in variantTypes:
                variantTypeToken = getVariantMatrixToken(variantType)
                matchingVariants = [
                    variant for variant in variants
                    if variant["axisValues"][axisIndex] == value["index"] and variant["type"] == variantType
                ]

                if len(matchingVariants) == 0:
                    continue

                macroName = f"RTX_SHADER_VARIANT_MATRIX_{baseToken}_{axisToken}_{valueToken}_{variantTypeToken}"
                lines.append(f"#define {macroName}(X) \\")

                for i, variant in enumerate(matchingVariants):
                    axisValues = ", ".join([str(axisValue) for axisValue in variant["axisValues"]])
                    line = f"  X({axisValues}, {variant['name']})"
                    if i != len(matchingVariants) - 1:
                        line += " \\"
                    lines.append(line)

                lines.append("")

    contents = "\n".join(lines)
    if args.build_stamp and not args.binary:
        contents = shader_generation_include(args.output, args.build_stamp) + contents
    if os.path.exists(destFile):
        with open(destFile, "r") as file:
            if file.read() == contents:
                return

    with open(destFile, "w") as file:
        file.write(contents)

def expandVariantMatrix(inputFile, lineno, inputType, matrix):
    axes = matrix["axes"]
    axisOrder = matrix["order"] if matrix["order"] is not None else list(axes.keys())

    if len(axisOrder) == 0:
        print(f'{inputFile}:{lineno}: variant matrix has no axes')
        return []

    if len(axisOrder) != len(set(axisOrder)):
        print(f'{inputFile}:{lineno}: variant matrix order contains a duplicate axis')
        return []

    for axisName in axisOrder:
        if axisName not in axes:
            print(f'{inputFile}:{lineno}: variant matrix order references unknown axis "{axisName}"')
            return []

    for axisName in axes:
        if axisName not in axisOrder:
            print(f'{inputFile}:{lineno}: variant matrix axis "{axisName}" is not present in the order')
            return []

    combinations = [([], None, list(matrix["common"]), [])]

    for axisName in axisOrder:
        nextCombinations = []
        for value in axes[axisName]:
            suffixName, suffixType = getVariantMatrixSuffixNameAndType(inputFile, lineno, value["suffix"])

            if suffixName is None:
                return []

            for nameParts, variantType, variantDefines, axisValues in combinations:
                if suffixType != "" and variantType is not None and suffixType != variantType:
                    print(f'{inputFile}:{lineno}: variant matrix generated conflicting shader types "{variantType}" and "{suffixType}"')
                    return []

                nextNameParts = list(nameParts)
                if suffixName != "":
                    nextNameParts.append(suffixName)

                nextVariantType = suffixType if suffixType != "" else variantType
                nextCombinations.append((nextNameParts, nextVariantType, variantDefines + value["defines"], axisValues + [value["index"]]))

        combinations = nextCombinations

    result = []
    variants = []
    for nameParts, variantType, defines, axisValues in combinations:
        if variantType is None:
            if len(inputType) == 0:
                print(f'{inputFile}:{lineno}: shader type not specified in matrix axis or in the file name')
                return []
            variantType = inputType

        variantName = matrix["base"]
        if len(nameParts) != 0:
            variantName += "_" + "_".join(nameParts)

        variants.append({
            "name": variantName,
            "type": variantType,
            "axisValues": axisValues,
        })
        result.append([variantName + variantType] + defines)

    writeVariantMatrixHeader(inputFile, matrix, axisOrder, variants)

    return result

def parseShaderVariants(inputFile):
    result = []
    lineno = 0
    inputWithType = getShaderName(inputFile)
    inputName, inputType = os.path.splitext(inputWithType)
    endvariantsFound = False
    matrix = None
    with open(inputFile, "r") as file:
        for line in file:
            lineno += 1
            if line.startswith("//!variant-matrix"):
                if len(result) != 0 or matrix is not None:
                    print(f'{inputFile}:{lineno}: variant matrix must be the first variant declaration')
                    return []

                parts = line.split()
                if len(parts) != 2:
                    print(f'{inputFile}:{lineno}: invalid shader variant matrix specification')
                    return []

                matrix = {
                    "base": parts[1],
                    "common": [],
                    "axes": {},
                    "order": None,
                }

            elif line.startswith("//!variant"):
                if matrix is not None:
                    print(f'{inputFile}:{lineno}: explicit variants cannot be mixed with a variant matrix')
                    return []

                parts = line.split()
                if len(parts) < 2:
                    print(f'{inputFile}:{lineno}: invalid shader variant specification')
                    return []

                # Parse the variant name and see if it has a shader type override
                variantName, variantType = os.path.splitext(parts[1])
                if len(variantType) != 0:
                    variantWithType = parts[1]
                else:
                    if len(inputType) == 0:
                        print(f'{inputFile}:{lineno}: shader type not specified here or in the file name')
                        return []
                    variantWithType = variantName + inputType

                # Concatenate the variant name back with the defines
                result.append([variantWithType] + parts[2:])

            elif line.startswith("//!>"):
                parts = line.split()
                if matrix is not None:
                    if len(parts) < 2:
                        continue

                    command = parts[1]
                    if command == "common":
                        matrix["common"] += parts[2:]
                    elif command == "axis":
                        if len(parts) < 4:
                            print(f'{inputFile}:{lineno}: invalid variant matrix axis declaration')
                            return []

                        axisName = parts[2]
                        suffix = parts[3]
                        if axisName not in matrix["axes"]:
                            matrix["axes"][axisName] = []

                        suffixName, _ = getVariantMatrixSuffixNameAndType(inputFile, lineno, suffix)
                        if suffixName is None:
                            return []

                        label = "none" if suffix == "-" else suffixName
                        matrix["axes"][axisName].append({
                            "suffix": suffix,
                            "defines": parts[4:],
                            "label": label,
                            "index": len(matrix["axes"][axisName]),
                        })
                    elif command == "order":
                        matrix["order"] = parts[2:]
                    else:
                        print(f'{inputFile}:{lineno}: unknown variant matrix command "{command}"')
                        return []

                    continue

                if len(result) == 0:
                    print(f'{inputFile}:{lineno}: variant continuation must follow a declaration')
                    return []

                if len(parts) > 1:
                    # Append the declarations found on this line to the previous variant
                    result[-1] += parts[1:]

            elif line.startswith("//!end-variants"):
                endvariantsFound = True
                if matrix is not None:
                    result = expandVariantMatrix(inputFile, lineno, inputType, matrix)
                break

            elif (line.startswith("//!") or line.startswith("//>")) and len(result) != 0:
                print(f'{inputFile}:{lineno}: warning: this looks like a variant declaration but is not one')

    if matrix is not None:
        if not endvariantsFound:
            print(f'{inputFile}:{lineno}: no !end-variants found in the file')
        return result
    elif len(result) == 0:
        result = [[inputWithType]]
    elif not endvariantsFound:
        # If there are any !variant declarations, there must be an !endvariants statement somewhere
        print(f'{inputFile}:{lineno}: no !end-variants found in the file')
        return []
    return result

tasks = []
embeddedShaders = []
slangVariantNames = set()

for root, dirs, files in os.walk(args.input):
    for name in files:
        task = None
        inputFile = os.path.join(root, name)
        if name.endswith(".comp") \
        or name.endswith(".vert") \
        or name.endswith(".geom") \
        or name.endswith(".frag") \
        or name.endswith(".rgen") \
        or name.endswith(".rchit") \
        or name.endswith(".rahit") \
        or name.endswith(".rmiss") \
        or name.endswith(".rint") \
        or name.endswith(".rcall"):
            task = createGlslangTask(inputFile)
            if task.needsBuild():
                tasks.append(task)

        elif name.endswith(".slang"):
            # Slang library modules are compiled through their shader entry's imports.
            with open(inputFile, encoding="utf-8-sig") as sourceFile:
                isModule = any(re.match(r'^\s*(?:module|implementing)\s+\w+\s*;', line) for line in sourceFile)
            if isModule:
                continue

            variants = parseShaderVariants(inputFile)

            if len(variants) == 0:
                # Couldn't parse the variant specifications, exit with an error code
                sys.exit(2)

            # Create tasks for each variant
            for variantSpec in variants:
                slangVariantNames.add(os.path.splitext(variantSpec[0])[0])
                task = createSlangTask(inputFile, variantSpec)
                if not args.binary:
                    embeddedShaders.append((task.outputs[0], task.outputs[2]))
                if task.needsBuild():
                    tasks.append(task)

missingDebugVariants = set(args.slang_debug_variants) - slangVariantNames
if missingDebugVariants:
    parser.error('Unknown Slang debug variants: ' + ', '.join(sorted(missingDebugVariants)))

if args.slang_debug_variants:
    print('Slang source debug variants: ' + ', '.join(sorted(set(args.slang_debug_variants))))

if len(tasks):
    threads = []
    threadCount = multiprocessing.cpu_count() if args.parallel else 1
    for i in range(threadCount):
        thread = threading.Thread(target = runTasks, args = (tasks,))
        thread.start()
        threads.append(thread)
        
    for thread in threads:
        thread.join()

if terminate:
    sys.exit(1)

dependencyCache.save()

if not args.binary:
    if args.shared_header:
        prefix = shader_generation_include(args.output, args.build_stamp) if args.build_stamp else ''
        embedded = deduplicate_shaders(embeddedShaders, args.shared_header, args.shared_sources, prefix)
        print(f"Shared {embedded['sharedGroups']} SPIR-V groups, removed {embedded['savedBytes']} duplicate bytes")
    else:
        restore_inline_shaders(embeddedShaders)

if args.build_stamp and not args.binary:
    publish_shader_generation(args.output, args.build_stamp)
