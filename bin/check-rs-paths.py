#!/usr/bin/env python3
"""Find any embedded Rust SOURCE path in a shipped artifact's bytes.

Read by `bin/check-build-paths` (DECISIONS.md#reproducible-build-paths). A
release build drops the panic-location machinery entirely
(`-Zlocation-detail=none`, `bin/rust-release-flags`), so a `panic!`/
`unwrap()`/`assert!` site no longer embeds a file path at all; this is what
proves that, directly on the bytes, rather than trusting the build script.

THIS IS NOT A PLAIN GREP, for three bugs found by running early versions
against a binary that should have been clean, or dirty, and getting the
wrong answer either way (AGENTS.md section 5 -- a check that only ever
passes is no check, and the fixes below came from probing a real artifact
each time, not from reading the regex and finding it sensible):

1. A naive regex with no real word-boundary check still "passes" a
   negative-lookbehind-for-backtick test: `(?<!`)PATTERN` correctly refuses
   to start a match right after a backtick, but the regex engine then just
   tries the NEXT position one character in (which is no longer preceded by
   a backtick) and matches a path one character short -- `` `cli/src/main.rs`
   `` still yields a hit for `li/src/main.rs`. The fix is a real boundary
   assertion before the lookbehind check, not just the lookbehind alone: a
   match may only START right after a byte that is not itself part of a
   path (or at the start of the file).

1a. That boundary assertion's own first version then matched NOTHING for any
    path with a leading `/` -- `/rustc/<hash>/library/alloc/src/fmt.rs` came
    back truncated to `library/alloc/src/fmt.rs`, silently, because `/` was
    included in the "blocks a boundary" character class, so the position
    right after a leading `/` (preceded by that same `/`) was never
    considered a valid start. `/` is always a legitimate separator and must
    NOT block a boundary; only an identifier character (letter, digit, `_`,
    `@`, `.`, `-`) immediately before the match may do that. Found by
    checking where a flagged, clean binary's reported hits actually started
    in the raw bytes, not by rereading the pattern.

2. Several first-party `.rs` paths ARE present in release artifacts on
   purpose, and are not panic locations: doc comments (and, in `flintc.wasm`
   /`flintc.bytecode` -- the SELF-HOSTED compiler, which is flint source, not
   Rust -- flint docstrings) that cross-reference another file by name for a
   human reader, e.g. `cli/src/main.rs`'s own doc comment mentioning
   `` `runtime/src/aot.rs` ``. Surveyed across all seven checked artifacts
   (2026-10-08): every hit past the sysroot exclusions below was one of
   these, always immediately preceded by a backtick. Excluding exactly that
   -- not a hardcoded filename list -- is what tells a doc-comment mention
   apart from a real embedded Location.

THE REMAINING EXCLUSIONS are the prebuilt std sysroot's OWN paths, baked in
by the Rust project's release build of std before this build ever runs,
identical for anyone on this exact rustc, and not something
`-Zlocation-detail` touches (it flags the CALLING crate's compilation, not
std's): `/rustc/<hash>/library/...` (std's own source, the form most of it
takes), a bare `library/...` with NO `/rustc/<hash>/` prefix at all (found
on an UNFLAGGED build carrying 67 of these -- e.g. a literal, contiguous,
NUL-terminated `library/std/src/panicking.rs` with nothing resembling a hash
before it -- confirmed by reading the raw bytes around one rather than
assuming the prefixed form is the only one this toolchain's std produces),
and `/cargo/registry/<hash>/...` (the backtrace machinery's own vendored
deps -- addr2line, gimli, rustc-demangle, hashbrown -- which is a DIFFERENT
path shape from OUR remap target, `/cargo-registry` with no internal slash,
chosen in `bin/rust-remap-flags` precisely so the two cannot collide).

Exit 0 and silent if clean; prints each hit (one per line) and exits 1
otherwise.
"""
import re
import sys

PATTERN = re.compile(
    rb"(?:(?<=^)|(?<=[^A-Za-z0-9_@.-]))"
    rb"((?:[A-Za-z0-9_@.-]+/){2,}[A-Za-z0-9_.@-]*\.rs)"
)
SYSROOT = re.compile(rb"^/?rustc/|^/?cargo/registry/|^library/")


def hits(path):
    data = open(path, "rb").read()
    found = set()
    for m in PATTERN.finditer(data):
        start = m.start(1)
        if data[start - 1 : start] == b"`":
            continue  # a doc-comment cross-reference, not a compiled Location
        g = m.group(1)
        if SYSROOT.match(g):
            continue  # the prebuilt std sysroot's own path, not ours to fix
        found.add(g)
    return sorted(found)


if __name__ == "__main__":
    bad = False
    for path in sys.argv[1:]:
        for h in hits(path):
            print(f"{path}: {h.decode('latin1')}")
            bad = True
    sys.exit(1 if bad else 0)
