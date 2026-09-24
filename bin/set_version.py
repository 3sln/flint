"""Propagate `meta.edn`'s `:version` into every manifest.

`--check` reports instead of writing, which is `bin/check-version`.

WHY A LIST AND NOT A GLOB. A glob would reach into `.claude/worktrees/`, which
carries a full copy of the tree per agent -- 130 more manifests, none of them
this repo's. It would also silently pick up a new manifest without anyone
deciding whether it should carry flint's version, which is the decision this
file exists to make explicit.
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

def read_meta_version():
    """`:version` out of `meta.edn`, without an edn parser.

    A regex rather than a reader, because this runs from `sh` before anything
    else is built and must not need babashka on the path. It is anchored on the
    KEY so a version elsewhere in the file cannot be picked up by position, and
    it refuses rather than guessing if the key is absent -- a propagation that
    silently wrote the empty string into sixteen manifests would be worse than
    not running.
    """
    t = (ROOT / "meta.edn").read_text()
    # Comment lines first: `;; ... :version "2026.9.24" ...` is prose in this
    # file, and matching it would publish an example.
    live = "\n".join(l for l in t.splitlines() if not l.lstrip().startswith(";"))
    m = re.search(r':version\s+"([^"]+)"', live)
    if not m:
        raise SystemExit("meta.edn has no `:version \"...\"` outside its comments")
    return m.group(1)

WANT = read_meta_version()

CARGO = [
    "cli/Cargo.toml", "nativeabi/Cargo.toml", "runtime/Cargo.toml",
    "sdks/c/Cargo.toml", "sdks/rust/Cargo.toml",
    "units-src/flint-conc/Cargo.toml", "units-src/flint-data-html/Cargo.toml",
    "units-src/flint-data-json/Cargo.toml", "units-src/flint-data-xml/Cargo.toml",
    "units-src/flint-demo-shout/Cargo.toml", "units-src/flint-snap/Cargo.toml",
]
# `deck/` is a slide site rather than a published flint artifact, and it is here
# deliberately: leaving one manifest out is how the drift came back last time.
NPM = ["package.json", "deck/package.json", "sdks/cli/package.json",
       "sdks/esm/package.json"]
# The value that actually reaches a compiled artifact, via `modmeta`. A literal
# rather than a read of `VERSION`, because `bin/flint` has to work from a
# distribution that carries no repo.
FLINT = "bin/flint"

def semver_ok(v):
    return re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?", v)

def cargo_get(text):
    m = re.search(r'(?m)^version\s*=\s*"([^"]*)"', text)
    return m.group(1) if m else None

def cargo_set(text, v):
    return re.sub(r'(?m)^(version\s*=\s*")[^"]*(")', lambda m: m.group(1) + v + m.group(2),
                  text, count=1)

def npm_get(text):
    m = re.search(r'"version"\s*:\s*"([^"]*)"', text)
    return m.group(1) if m else None

def npm_set(text, v):
    """A REGEX AND NOT `json.dumps`. Round-tripping the file reformatted it --
    `sdks/esm/package.json`'s one-line `keywords` array became seven lines, a
    20-line diff for a 3-character change. A manifest belongs to whoever
    formatted it."""
    return re.sub(r'("version"\s*:\s*")[^"]*(")', lambda m: m.group(1) + v + m.group(2),
                  text, count=1)

def flint_get(text):
    m = re.search(r'FLINT_VERSION"\)\s*"([^"]*)"', text)
    return m.group(1) if m else None

def flint_set(text, v):
    return re.sub(r'(FLINT_VERSION"\)\s*")[^"]*(")', lambda m: m.group(1) + v + m.group(2),
                  text, count=1)

def main():
    check = "--check" in sys.argv
    if not semver_ok(WANT):
        print(f"meta.edn says `{WANT}`, which is not semver "
              f"(npm and cargo both refuse a date with hyphens or leading zeros)")
        return 1
    bad, n = [], 0
    for rel in CARGO:
        p = ROOT / rel
        t = p.read_text(); got = cargo_get(t); n += 1
        if got != WANT:
            bad.append(f"{rel}: {got}")
            if not check:
                p.write_text(cargo_set(t, WANT))
    for rel in NPM:
        p = ROOT / rel
        t = p.read_text(); got = npm_get(t); n += 1
        if got != WANT:
            bad.append(f"{rel}: {got}")
            if not check:
                p.write_text(npm_set(t, WANT))
    p = ROOT / FLINT
    t = p.read_text(); got = flint_get(t); n += 1
    if got is None:
        bad.append(f"{FLINT}: no FLINT_VERSION fallback found -- the pattern moved")
    elif got != WANT:
        bad.append(f"{FLINT}: {got}")
        if not check:
            p.write_text(flint_set(t, WANT))
    if check:
        if bad:
            print(f"check-version: {len(bad)} of {n} disagree with meta.edn ({WANT}):")
            for b in bad:
                print(f"  {b}")
            print("  fix with: ./bin/set-version")
            return 1
        print(f"check-version: {n} manifests all state {WANT}")
        return 0
    print(f"set-version: {WANT} into {n} manifests ({len(bad)} changed)")
    return 0

sys.exit(main())
