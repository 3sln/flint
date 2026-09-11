#!/usr/bin/env python3
"""Every decision citation in the tree resolves to a real section.

WHAT THIS USED TO CHECK, AND WHY IT CAUGHT NOTHING. Until 2026-09-11 each
decision lived in its own file with a status banner, and an index carried a
status row per decision. This script asserted the two AGREED, with a comment
saying the failure it catches "is not cosmetic: the index has already been
wrong in both directions".

It never fired, and it could not have. The index rows were near-verbatim
copies of the banners they were checked against, so the two agreed by
construction -- including when both were false. A verification pass found one
decision opening "NOT BUILT -- a spike, nothing in the tree uses it yet"
beside 89 sources generating 88 modules, another saying a data type "does not
exist" beside 552 lines implementing it, and an index whose adjacent rows
contradicted each other. This check passed through all of it.

A check that compares two copies of one claim is not a check. What it should
have compared the banner against is the CODE, which no script can do.

WHAT IT CHECKS NOW is the thing a script genuinely can: that the citations
resolve. 400-odd files cite decisions by slug -- `DECISIONS.md#strings-and-matching`
-- and a renamed, merged or deleted section turns every one of them into a
dangling pointer that nothing would otherwise notice. That is a real failure
with a mechanical answer, which is the kind worth automating.
"""
import os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, 'DECISIONS.md')

def main():
    errs = []
    text = open(DOC, encoding='utf-8').read()
    heads = re.findall(r'^## (.+)$', text, re.M)
    slugs = [h.strip() for h in heads]
    seen = set()
    for s in slugs:
        if s in seen:
            errs.append(f"DECISIONS.md has two sections called {s!r} -- an anchor can only mean one")
        seen.add(s)

    files = subprocess.run(['git', 'ls-files'], cwd=ROOT, capture_output=True,
                           text=True).stdout.split()
    cited, sites = set(), {}
    for f in files:
        p = os.path.join(ROOT, f)
        if not os.path.isfile(p):
            continue
        try:
            t = open(p, encoding='utf-8').read()
        except (UnicodeDecodeError, OSError):
            continue
        for m in re.finditer(r'DECISIONS\.md#([a-z0-9-]+)', t):
            cited.add(m.group(1))
            sites.setdefault(m.group(1), []).append(f)

    for c in sorted(cited):
        if c not in seen:
            where = sites[c][:3]
            errs.append(f"nothing in DECISIONS.md anchors {c!r}, cited by {', '.join(where)}"
                        + (f" and {len(sites[c]) - 3} more" if len(sites[c]) > 3 else ""))

    if errs:
        for e in errs:
            print("  FAIL " + e)
        print(f"check-decisions: {len(errs)} failure(s)")
        return 1
    print(f"check-decisions: {len(seen)} decisions, {len(cited)} cited by slug, every citation resolves")
    return 0

if __name__ == '__main__':
    sys.exit(main())
