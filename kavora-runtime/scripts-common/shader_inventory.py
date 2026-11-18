# SPDX-License-Identifier: MIT
"""Give Ninja a restatable dependency for shader additions and removals."""

import os
from pathlib import Path
import sys


def publish_inventory(root, destination):
    paths = (
        (Path(directory) / name).relative_to(root).as_posix()
        for directory, _, files in os.walk(root) for name in files
    )
    contents = "".join(f"{path}\n" for path in sorted(paths)).encode("utf-8")
    if destination.exists() and destination.read_bytes() == contents:
        return
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    temporary.write_bytes(contents)
    os.replace(temporary, destination)


if __name__ == "__main__":
    publish_inventory(Path(sys.argv[1]), Path(sys.argv[2]))
