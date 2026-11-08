import argparse
import os

def write_c_array(stream, data, array_name, write_size=True):
    length = len(data)
    padding = (4 - (length % 4)) % 4
    data += b'\x00' * padding
    stream.write(f"const uint32_t {array_name}[] = {{")
    for i in range(0, len(data), 4):
        if (i // 4) % 8 == 0:
            stream.write('\n    ')
        val = int.from_bytes(data[i:i + 4], 'little')
        stream.write(f'0x{val:08x}, ')
    stream.write("\n};\n\n")
    if write_size:
        stream.write(f"const size_t {array_name}_sizeInBytes = {length};\n")


def xxd_c_array(data, output_filename):
    array_name = os.path.basename(output_filename).removesuffix('.h')

    # A terminated conversion must not leave a newer, truncated build output.
    temporary_filename = str(output_filename) + '.tmp'
    with open(temporary_filename, 'w', encoding='utf-8') as f:
        write_c_array(f, data, array_name)
    os.replace(temporary_filename, output_filename)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description = 'Convert SPV to C header')
    parser.add_argument('--input', '-i', help = "Input SPV", required=True)
    parser.add_argument('--output', '-o', help = "Output header", required=True)
    args = parser.parse_args()
    if not os.path.exists(args.input):
        print(args.input + " file not found.")
        exit(1)

    with open(args.input, 'rb') as f:
        xxd_c_array(f.read(), args.output)
