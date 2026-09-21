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

AND, SINCE 2026-09-20, THAT EACH SECTION CARRIES EXACTLY ONE STATUS BANNER.
That is structure rather than content, so the objection above does not apply:
it says nothing about whether a status is TRUE, only that there is one claim
to argue with rather than none or two.

Both failures had already happened. `other-hosts` carried TWO banners that
disagreed about whether native AOT was built -- a later edit had spliced a new
one into the middle of the old prose, leaving a sentence stopped at "see" and
the closing paragraph duplicated -- and a reader who scrolled to the first
banner got the opposite answer from one who scrolled to the second. Separately,
eight sections once carried NO banner at all, and the triage that noticed
named only one of the eight, because nobody had counted.

Neither is a judgement call, which is what makes it automatable: a section
either has one banner or it does not.
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

    # EXACTLY ONE STATUS BANNER PER SECTION. A section is everything from its
    # `## ` heading to the next one, and a banner is a line beginning
    # `**Status`. Two means an edit spliced one in rather than replacing the
    # old; zero means a claim nobody can argue with, because there is none.
    #
    # A SECTION IS A DECISION IFF IT CARRIES A `**Ratified:**` LINE, which is
    # how the file already distinguishes one -- the preamble "about this file,
    # and the sign-off" is a `## ` heading and is not a decision. Keying on the
    # heading TEXT would have meant a hardcoded name here, and a second
    # non-decision section would then be silently required to have a status.
    #
    # But "no Ratified line" must not become a way to opt out, so the count of
    # such sections is asserted too: exactly one, the preamble. A new section
    # that forgets both lines is still caught, by the second check rather than
    # the first.
    bodies = re.split(r'^## ', text, flags=re.M)[1:]
    not_decisions = []
    for body in bodies:
        name = body.split('\n', 1)[0].strip()
        if not re.search(r'^\*\*Ratified:', body, re.M):
            not_decisions.append(name)
            continue
        n = len(re.findall(r'^\*\*Status', body, re.M))
        if n == 0:
            errs.append(f"{name!r} has no status line -- a decision with no claim "
                        f"cannot be checked against the code")
        elif n > 1:
            errs.append(f"{name!r} has {n} status lines -- an edit spliced one in "
                        f"rather than replacing it, and they can disagree")
    if len(not_decisions) != 1:
        errs.append(f"{len(not_decisions)} sections carry no `**Ratified:**` line "
                    f"({', '.join(repr(n) for n in not_decisions) or 'none'}) -- "
                    f"exactly one, the preamble, is expected; a decision without "
                    f"one is skipped by the status check above")

    # TRACKED *AND* UNTRACKED-BUT-NOT-IGNORED. Plain `git ls-files` lists only
    # tracked files, so a brand-new file's citations went unchecked until the
    # commit that added it -- `bin/check` passed, the commit landed, and the
    # broken anchor surfaced on the NEXT run. That is a check that reports green
    # on precisely the change it exists to examine.
    #
    # `--exclude-standard` keeps `.gitignore` honoured, so build output and
    # `dist/` stay out.
    files = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard'],
                           cwd=ROOT, capture_output=True, text=True).stdout.split()
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
    print(f"check-decisions: {len(seen) - len(not_decisions)} decisions, one status "
          f"line each, {len(cited)} cited by slug, every citation resolves")
    return 0

if __name__ == '__main__':
    sys.exit(main())
