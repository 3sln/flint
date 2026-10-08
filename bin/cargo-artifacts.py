#!/usr/bin/env python3
"""Read cargo's own `--message-format=json-render-diagnostics` stream, so
`bin/build-units` never has to guess WHERE cargo put an artifact.

DECISIONS.md#pin-the-nightly-toolchain: the old `rlib()` was
`ls target/.../release/deps/lib$1-*.rlib`, a directory guess that broke the
moment a nightly moved a build-script-bearing dependency's own rlib (`libm`)
into its OWN `build/<crate>/<hash>/out/` instead of `release/deps/`. Pinning
the nightly stopped THAT layout from moving underfoot; it does not make the
lookup itself survive the NEXT one that does, which is the "risk left open"
that decision records. Reading cargo's own artifact list rather than a path
convention closes it for good: wherever cargo puts an rlib, cargo's own JSON
says so.

Two jobs, one on each side of the build:

    rlibs <json-log> <dest-dir>
        Copy every `.rlib` AND `.rmeta` any `compiler-artifact` message named
        into <dest-dir>, under its own real basename (already carries the
        crate name and its hash, e.g. `liblibm-<hash>.rlib`), unless a file by
        that name is already there. The `.rmeta` matters as much as the
        `.rlib` now: on the nightly this was written against, a
        build-script-bearing dependency's `.rlib` in its own `build/.../out/`
        is a METADATA STUB ("only metadata stub found ... please provide path
        to the corresponding .rmeta file with full metadata" from a direct
        `rustc --extern libm=<path>.rlib` otherwise) and rustc finds the full
        metadata by looking for a sibling `.rmeta` with the same stem IN THE
        SAME DIRECTORY as the `.rlib` it was given -- so the two have to travel
        together. Idempotent, and safe to call whether the artifact already
        lived in <dest-dir> or not -- this is what makes the existing
        `rlib() { ls "$DEPS"/lib$1-*.rlib; }` glob (and every other script
        that searches `$DEPS` by convention, e.g. `bin/build-test-unit`'s
        `-L $DEPS`) correct again without a second copy of this logic there.

    diagnostics <json-log>
        Print the human-rendered text of every `compiler-message` in the log,
        oldest first -- what used to be approximated by `tail -30` on a plain
        build log, except it cannot be fooled by warnings that sort after the
        one real error (DECISIONS.md#pin-the-nightly-toolchain's own CI
        incident: 52 warnings, then the real `error[...]` scrolled off a
        `tail -20`).
"""
import json
import os
import shutil
import sys


def each_message(log_path):
    with open(log_path, "r", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except ValueError:
                # Not every line cargo emits alongside `--message-format=json`
                # is JSON (a build script can write to its own stdout, which
                # cargo does not wrap) -- skip rather than abort on it.
                continue


def collect_rlibs(log_path, dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    for msg in each_message(log_path):
        if msg.get("reason") != "compiler-artifact":
            continue
        for path in msg.get("filenames") or []:
            if not (path.endswith(".rlib") or path.endswith(".rmeta")):
                continue
            base = os.path.basename(path)
            dest = os.path.join(dest_dir, base)
            if os.path.abspath(path) == os.path.abspath(dest):
                continue
            if not os.path.exists(dest) and os.path.exists(path):
                shutil.copy2(path, dest)


def print_diagnostics(log_path):
    for msg in each_message(log_path):
        if msg.get("reason") != "compiler-message":
            continue
        rendered = (msg.get("message") or {}).get("rendered")
        if rendered:
            sys.stdout.write(rendered)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "rlibs" and len(sys.argv) == 4:
        collect_rlibs(sys.argv[2], sys.argv[3])
    elif cmd == "diagnostics" and len(sys.argv) == 3:
        print_diagnostics(sys.argv[2])
    else:
        sys.stderr.write(
            "usage: cargo-artifacts.py rlibs <json-log> <dest-dir>\n"
            "       cargo-artifacts.py diagnostics <json-log>\n"
        )
        sys.exit(1)
