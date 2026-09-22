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

# A citation in one of the two house forms: `sym` (`path:line`) and
# (`sym`, `path:line`). Deliberately narrow -- see the loop that uses it.
CITE = re.compile(r'`([A-Za-z_][\w:.\-/]*)`[,)]? *\(?`([\w./-]+\.(?:rs|java|cs|cljc|clj|mjs)):(\d+)`')
WIN = 8   # lines either side; code moves a little without the citation being wrong

def fold(s):
    """`Rt::lock_intern`, `lockIntern` and `lock-intern` are one name here.

    The ports spell the same thing three ways and the record cites whichever
    it was discussing, so a comparison that respects case and separators
    reports drift that is only spelling.
    """
    return s.replace('_', '').replace('-', '').replace('/', '').replace('.', '').lower()

def main():
    errs = []
    cited_lines = []
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

    # A CITED `symbol` (`path:line`) STILL HAS THAT SYMBOL AT THAT LINE. Line
    # numbers rot silently and in the direction that reads like success: the
    # file is still there, the number is still in range, and the line now holds
    # unrelated code. A sweep on 2026-09-22 found ten -- `reap_ports` cited at
    # `conc.rs:3045` in a 2333-line file AND at `conc.rs:1217` when it sits at
    # 2297, `TY_OPAQUE` cited 14 lines early, `Rt::var_named` 114 lines early.
    # Every one of them was true when it was written.
    #
    # WHY ONLY THE HOUSE FORM. The general question -- "which symbol is this
    # citation about?" -- has no mechanical answer. Guessing it (nearest
    # backticked word before the number) flagged four CORRECT citations out of
    # thirty, and a gate with a 13% false-positive rate is a gate somebody
    # switches off. So this reads only the forms that put the subject next to
    # the number, and says nothing about the rest: eight citations today, all
    # passing. A narrow check that can gate beats a broad one that cannot.
    #
    # A bare filename (`conc.rs:900`) is skipped: it is prose, not a pointer.
    for m in CITE.finditer(text):
        sym, path, n = m.group(1), m.group(2), int(m.group(3))
        if '/' not in path:
            continue
        # TWO CITATIONS IN A ROW are not a symbol and its line. `` `bin/flint:861`,
        # `cli/src/sys.rs:840` `` matches the house form with the FIRST citation
        # standing where the symbol goes, and `bin/flint` is then looked for
        # inside sys.rs and not found -- a correct pair reported as drift. Found
        # in `doc/goals/kin-port.md` while measuring whether this check was worth
        # pointing at the goal docs (it is not -- one citation in the house form
        # across all of them, and it was this artefact).
        if re.search(r':\d+$', sym):
            continue
        p = os.path.join(ROOT, path)
        if not os.path.isfile(p):
            errs.append(f"DECISIONS.md cites `{sym}` at {path}:{n}, and there is no such file")
            continue
        lines = open(p, encoding='utf-8', errors='replace').read().split('\n')
        if n > len(lines):
            errs.append(f"DECISIONS.md cites `{sym}` at {path}:{n}, past the end of "
                        f"a {len(lines)}-line file")
            continue
        leaf = fold(sym.split('::')[-1].split('/')[-1].split('.')[-1])
        lo, hi = max(0, n - 1 - WIN), min(len(lines), n + WIN)
        if not any(leaf in fold(l) for l in lines[lo:hi]):
            at = [i + 1 for i, l in enumerate(lines) if leaf in fold(l)]
            where = (f" -- it is at {', '.join(map(str, at[:4]))}" if at
                     else " -- it is not in that file at all")
            errs.append(f"DECISIONS.md cites `{sym}` at {path}:{n}, which is not within "
                        f"{WIN} lines of there{where}")
        cited_lines.append(path)

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
          f"line each, {len(cited)} cited by slug, every citation resolves, "
          f"{len(cited_lines)} symbol+line citations still point at their symbol")
    return 0

if __name__ == '__main__':
    sys.exit(main())
