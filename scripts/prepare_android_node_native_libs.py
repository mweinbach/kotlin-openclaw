#!/usr/bin/env python3

import argparse
import os
import posixpath
import shutil
import tarfile
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", required=True)
    parser.add_argument("--output-dir", required=True)
    return parser.parse_args()


def normalize(name: str) -> str:
    return name[2:] if name.startswith("./") else name


def is_elf(data: bytes) -> bool:
    return data.startswith(b"\x7fELF")


def patch_elf_names(data: bytes, replacements: dict[str, str]) -> bytes:
    if not is_elf(data):
        return data
    patched = data
    for old_name, new_name in replacements.items():
        if old_name == new_name:
            continue
        old_bytes = old_name.encode("utf-8")
        new_bytes = new_name.encode("utf-8")
        if len(new_bytes) > len(old_bytes):
            raise ValueError(f"Replacement '{new_name}' is longer than '{old_name}'")
        patched = patched.replace(old_bytes, new_bytes + (b"\0" * (len(old_bytes) - len(new_bytes))))
    return patched


def main():
    args = parse_args()
    archive_path = Path(args.archive)
    output_root = Path(args.output_dir)
    abi_dir = output_root / "arm64-v8a"

    if output_root.exists():
        shutil.rmtree(output_root)
    abi_dir.mkdir(parents=True, exist_ok=True)

    with tarfile.open(archive_path, "r:xz") as tar:
        members = {normalize(member.name): member for member in tar.getmembers()}

        def resolve_target(name: str) -> str:
            current = name
            seen = set()
            while True:
                if current in seen:
                    raise ValueError(f"Symlink loop while resolving {name}")
                seen.add(current)
                member = members[current]
                if not member.issym():
                    return current
                target = normalize(
                    posixpath.normpath(posixpath.join(posixpath.dirname(current), member.linkname))
                )
                current = target

        aliases_by_target: dict[str, set[str]] = {}
        for name, member in members.items():
            if not name.startswith("usr/lib/") or not member.issym():
                continue
            basename = posixpath.basename(name)
            if basename.startswith("._") or not basename.startswith("lib") or ".so" not in basename:
                continue
            try:
                resolved = resolve_target(name)
            except KeyError:
                continue
            aliases_by_target.setdefault(resolved, set()).add(basename)

        staged_files: dict[str, bytes] = {}
        replacements: dict[str, str] = {}

        for name, member in members.items():
            if posixpath.dirname(name) != "usr/lib" or not member.isfile():
                continue
            basename = posixpath.basename(name)
            if basename.startswith("._") or not basename.startswith("lib") or ".so" not in basename:
                continue

            aliases = sorted(
                alias for alias in aliases_by_target.get(name, set())
                if alias.endswith(".so")
            )
            if basename.endswith(".so"):
                output_name = basename
            elif aliases:
                output_name = aliases[0]
            else:
                output_name = basename

            fileobj = tar.extractfile(member)
            if fileobj is None:
                raise ValueError(f"Could not extract {name}")
            staged_files.setdefault(output_name, fileobj.read())

            for old_name in {basename, *aliases_by_target.get(name, set())}:
                replacements[old_name] = output_name

        for source_name, output_name in {
            "usr/bin/node": "libopenclaw_node.so",
            "usr/bin/rg": "libopenclaw_rg.so",
        }.items():
            member = members[source_name]
            fileobj = tar.extractfile(member)
            if fileobj is None:
                raise ValueError(f"Could not extract {source_name}")
            staged_files[output_name] = fileobj.read()

        for file_name, data in staged_files.items():
            patched = patch_elf_names(data, replacements)
            target_path = abi_dir / file_name
            target_path.write_bytes(patched)
            os.chmod(target_path, 0o755)


if __name__ == "__main__":
    main()
