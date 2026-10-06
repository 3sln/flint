#!/usr/bin/env python3
"""Every published surface has a section in `doc/api-review.md`.

Three kinds, because a surface is a surface whichever direction it faces:

* what a GUEST can name -- every namespace under `lib/`, and every served one
  in the catalogue the two CLIs agree on;
* what an EMBEDDER calls -- each package under `sdks/`;
* what a USER types -- each command the CLIs dispatch.

IT DOES NOT TICK THE BOXES. Only the maintainer does, the same rule
`DECISIONS.md` follows and for the same reason: a sign-off a script can produce
is not a sign-off.

It also fails on a section for something that no longer exists, because a list
that reads as covering more than it does is worse than a short one.
"""
import os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, 'doc', 'api-review.md')


def read(*parts):
    try:
        with open(os.path.join(ROOT, *parts), encoding='utf-8') as f:
            return f.read()
    except OSError:
        return ''


# `^:internal`/`^:private` ON THE `ns` FORM ITSELF
# (`DECISIONS.md#namespace-is-workspace-local`) sits between `(ns ` and the
# name, and this file's "first line" regexes anchored straight on
# `[a-z]` after the whitespace -- so `(ns ^:internal flint.nfa` matched
# nothing. Measured: marking `flint.nfa`/`flint.pike` that way made
# `namespaces()` stop finding them at all, and the checker reported their
# EXISTING `doc/api-review.md` sections as "no longer exists" -- the
# opposite of a false pass, but still wrong, and the two patterns below
# (`NS_HEAD` and its one other use in `lib_requirers`) are the only two
# places in this file that read a `ns` form's head rather than its require
# clauses.
NS_HEAD = r'\(ns\s+(?:\^\S+\s+)*'


def namespaces():
    """Every `lib/` namespace, by the name its own `ns` form gives."""
    out = set()
    for base, _, files in os.walk(os.path.join(ROOT, 'lib')):
        for f in files:
            if f.endswith('.cljc'):
                first = read(os.path.relpath(os.path.join(base, f), ROOT)).split('\n', 1)[0]
                m = re.match(NS_HEAD + r'([a-z][\w.-]*)', first)
                if m:
                    out.add(m.group(1))
    return out


def served():
    """The served namespaces, from the catalogue the two CLIs agree on."""
    return set(re.findall(r"^  \['([a-z][a-z.]*)'", read('sdks', 'cli', 'src', 'catalogue.mjs'), re.M))


def catalogue_vars():
    """Each served namespace's var names, in order, out of the catalogue itself.

    THE DOC RESTATED THIS AND DRIFTED. `flint.ception` was written down as "6
    public vars" and holds 8 -- `caller` and `close-caller` were never reviewed,
    on the one served namespace that is NOT capability-gated. AGENTS.md section 1
    says to make one list read the other, so this reads the list.
    """
    text = read('sdks', 'cli', 'src', 'catalogue.mjs')
    out = {}
    for ns, body in re.findall(r"\['([a-z][a-z.]*)',\s*\[(.*?)\n  \]\]", text, re.S):
        out[ns] = re.findall(r"\['([^']+)',", body)
    return out


def manifest_counts():
    """`:count` per namespace, out of `doc/manifest.edn`.

    THE ONLY COUNT WITH A PROOF BEHIND IT: `test/manifest.clj` compiles a program
    referencing every var each entry claims present, so `:count` is the number a
    program can actually name. The review restated it by hand and nine of the
    twenty-two disagreed -- in BOTH directions, so it was not one stale edit.

    A `defprotocol` is why several were low: it names the protocol AND its method
    vars, and a reader counting top-level forms sees one. `clojure.core.protocols`
    was written down as 0 and holds 4.

    THE PARSER IS BOUNDED PER ENTRY. A first attempt anchored the count to the
    entry's closing brace --
    only entries whose count is the LAST key -- and silently walked into the next
    namespace for the rest, reporting `clojure.edn` as 14 when it is 2. The
    coverage assertion below is what catches that: the entries parsed must be
    exactly the namespaces the README's generated table rows name.
    """
    text = read('doc', 'manifest.edn')
    starts = [(m.start(), m.group(1))
              for m in re.finditer(r'^[ {]([a-z][\w.]*)\n \{', text, re.M)]
    out = {}
    for i, (pos, ns) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(text)
        c = re.search(r':count (\d+)', text[pos:end])
        if c:
            out[ns] = int(c.group(1))
    return out


def readme_rows():
    """The namespaces the README's generated coverage table names."""
    rd = read('README.md')
    a, b = '<!-- BEGIN GENERATED COVERAGE -->', '<!-- END GENERATED COVERAGE -->'
    if a not in rd or b not in rd:
        return None
    return set(re.findall(r'^\|\s*`([a-z][\w.]*)`', rd[rd.index(a):rd.index(b)], re.M))


PUBLIC_EV = re.compile(
    r'\*\*Is this public\?\*\* Required by (\d+) compiled test program(?:\(s\)|s)?, '
    r'(\d+) other `lib` namespace(?:\(s\)|s)?, named (\d+) times?(?:\(s\))? in README\.')


def lib_requirers(ns):
    """`lib/` namespaces that `:require` `ns`, not counting its own file.

    The delimiter after the name is what keeps `flint.deps` from counting
    `[flint.deps.resolve ...]`: a prefix is not a requirer.
    """
    n = 0
    for base, _, files in os.walk(os.path.join(ROOT, 'lib')):
        for f in files:
            if not f.endswith('.cljc'):
                continue
            txt = read(os.path.relpath(os.path.join(base, f), ROOT))
            if re.match(NS_HEAD + re.escape(ns) + r'[\s)]', txt):
                continue
            if re.search(r'\[' + re.escape(ns) + r'[\s:\]]', txt):
                n += 1
    return n


def sdks():
    d = os.path.join(ROOT, 'sdks')
    return {f'sdks/{n}' for n in os.listdir(d) if os.path.isdir(os.path.join(d, n))}


ALIASES = {'-h': 'help', '--help': 'help', '-v': 'version', '--version': 'version'}


def native_commands():
    """The arms of `match argv[0].as_str()`, and nothing else.

    Scoped to that block on purpose: a plain grep for match arms also catches
    `match to.trim_start_matches(':')`, which made `llvm` and `native` look
    like commands. They are `:to` VALUES.
    """
    src = read('cli', 'src', 'main.rs')
    key = 'match argv[0].as_str() {'
    if key not in src:
        return None                      # shape changed: say so, do not pass
    i = src.index(key)
    depth, j = 0, i + len(key) - 1
    while j < len(src):
        if src[j] == '{':
            depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0:
                break
        j += 1
    block = src[i:j]
    out = set()
    for m in re.finditer(r'^        ((?:"[a-z-]+"\s*\|\s*)*"[a-z-]+")\s*=>', block, re.M):
        for c in re.findall(r'"([a-z-]+)"', m.group(1)):
            out.add(ALIASES.get(c, c))
    return out


def node_commands():
    src = read('sdks', 'cli', 'src', 'cli.mjs')
    out = {ALIASES.get(c, c) for c in re.findall(r"cmd === '([a-z-]+)'", src)}
    return out or None


def main():
    doc = read('doc', 'api-review.md')
    if not doc:
        print('  FAIL doc/api-review.md is missing')
        return 1
    # A SURFACE SECTION IS ONE WITH A BOX. `## ` is also used for prose
    # headings in the preamble, and treating those as surfaces made the check
    # report the header text as a namespace that no longer exists.
    have = set(re.findall(r'^## (.+)\n\n\*\*Reviewed:\*\*', doc, re.M))
    errs = []

    nat, nod = native_commands(), node_commands()
    if nat is None or nod is None:
        errs.append('could not read the command dispatch from one of the CLIs -- '
                    'the shape changed, so this check cannot speak for it')
        nat, nod = nat or set(), nod or set()

    # BOTH CLIS DISPATCH THE SAME SET. `flint wasm` existed on the native side
    # only for an afternoon, which is the drift this row exists to catch.
    for c in sorted(nat - nod):
        errs.append(f'`{c}` is a native CLI command with no npm CLI counterpart')
    for c in sorted(nod - nat):
        errs.append(f'`{c}` is an npm CLI command with no native counterpart')

    wanted = {}
    for ns in namespaces() | served():
        wanted[ns] = 'namespace'
    for s in sdks():
        wanted[s] = 'SDK'
    for c in nat | nod:
        wanted[f'cli:{c}'] = 'CLI command'

    for name, kind in sorted(wanted.items()):
        if name not in have:
            errs.append(f'{kind} `{name}` has no section in doc/api-review.md')

    # A section for something gone reads as coverage that is not there.
    for name in sorted(have):
        if name not in wanted:
            errs.append(f'doc/api-review.md has a section for `{name}`, which no longer exists')

    # EVERY SERVED SECTION'S VAR LIST, AGAINST THE CATALOGUE. A var added to one
    # of these is a new way to reach the filesystem, the network or the
    # environment, and it used to be able to arrive with the review still
    # reading as complete.
    cat = catalogue_vars()
    if set(cat) != served():
        errs.append('the catalogue parser and `served()` disagree about which '
                    'namespaces are in sdks/cli/src/catalogue.mjs -- its shape '
                    'changed, so this check cannot speak for it')
    for ns, names in sorted(cat.items()):
        m = re.search(r'^## ' + re.escape(ns) + r'\n\n\*\*Reviewed:.*?\n\n(\d+) public vars\. `([^`]*)`',
                      doc, re.M | re.S)
        if not m:
            errs.append(f'`{ns}` has no "N public vars. `...`" line in doc/api-review.md')
            continue
        said_n, said = int(m.group(1)), m.group(2).split()
        if said != names:
            errs.append(f'`{ns}` is reviewed as `{" ".join(said)}` and the catalogue '
                        f'holds `{" ".join(names)}`')
        elif said_n != len(names):
            errs.append(f'`{ns}` is reviewed as {said_n} public vars and the catalogue '
                        f'holds {len(names)}')

    # EVERY MANIFESTED NAMESPACE'S COUNT, against `doc/manifest.edn`.
    counts = manifest_counts()
    rows = readme_rows()
    if rows is None or set(counts) != rows:
        errs.append('the manifest parser and the README coverage table disagree about '
                    'which namespaces are manifested -- one of their shapes changed, so '
                    'this check cannot speak for the counts')
    else:
        for ns, n in sorted(counts.items()):
            m = re.search(r'^## ' + re.escape(ns) + r'\n\n\*\*Reviewed:.*?\n\n(\d+) public vars\.',
                          doc, re.M | re.S)
            if not m:
                errs.append(f'`{ns}` is in doc/manifest.edn and has no "N public vars" '
                            f'line in doc/api-review.md')
            elif int(m.group(1)) != n:
                errs.append(f'`{ns}` is reviewed as {m.group(1)} public vars and '
                            f'doc/manifest.edn counts {n}')

    # THE "Is this public?" EVIDENCE, for the two numbers that have a definition.
    # A wrong one is worse than a missing one: `flint.deps` read "1 other" and
    # concluded "required only by other `flint.deps.*` namespaces", when
    # `lib/flint/cli.cljc` requires it too -- so the row answered its own
    # question the wrong way. The TEST-PROGRAM count is left alone: nothing in
    # the tree defines what a "compiled test program" is countably, so checking
    # it would only pin whatever this script decided it meant.
    readme = read('README.md')
    found = 0
    for p in re.split(r'^## ', doc, flags=re.M)[1:]:
        ns = p.split('\n', 1)[0].strip()
        m = PUBLIC_EV.search(p)
        if not m:
            continue
        found += 1
        _, said_lib, said_rm = (int(g) for g in m.groups())
        real_lib = lib_requirers(ns)
        if said_lib != real_lib:
            errs.append(f'`{ns}` is recorded as required by {said_lib} other lib '
                        f'namespace(s) and {real_lib} require it')
        real_rm = len(re.findall(re.escape(ns), readme))
        if said_rm != real_rm:
            errs.append(f'`{ns}` is recorded as named {said_rm} time(s) in README '
                        f'and is named {real_rm}')
    # COVERAGE, not just findings: every evidence line must have been READ. A
    # third wording would otherwise be skipped in silence -- there are already
    # two, `program(s)` and `program`.
    present = doc.count('**Is this public?** Required by')
    if found != present:
        errs.append(f'{present} "Is this public?" lines are in doc/api-review.md and '
                    f'{found} were parsed -- one is worded in a way this check does not read')

    if errs:
        for e in errs:
            print(f'  FAIL {e}')
        print(f'check-api-review: {len(errs)} failure(s)')
        return 1

    total = len(re.findall(r'^\*\*Reviewed:\*\*', doc, re.M))
    signed = len(re.findall(r'^\*\*Reviewed:\*\* ☑', doc, re.M))
    kinds = f"{sum(1 for v in wanted.values() if v == 'namespace')} namespaces, " \
            f"{len(sdks())} SDKs, {len(nat | nod)} commands"
    print(f'  ok   api review: {total} surfaces ({kinds}), {signed} signed off')
    return 0


if __name__ == '__main__':
    sys.exit(main())
