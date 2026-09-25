#!/usr/bin/env python3
"""The artifact operations exist, and mean the same thing, on every target.

`runtimes/artifact-ops/contract.edn` is the contract as data and
`DECISIONS.md#four-operations` is the prose -- a slug that outlived the fourth
operation, `prop`, which was removed on 2026-09-24. This checks each target face
against the data, including that the removed one has not come back.

NOTHING HERE IS NAMED FOR THE COUNT. It was `check-four-ops` for a day and then
`prop` went and every name had to change. The number of operations lives in the
contract, where changing it is an edit to data.

WHY A GATE WITH ONE TARGET IN IT. Only the CLR face exists today. An assertion
that covers one target is still worth writing, and is in fact the cheapest
moment to write it: the second face is then checked the instant it appears,
against the contract, rather than against the first face by whoever remembers to
look. That is the failure this project has had four times -- sixteen version
declarations, four compile-spec front ends, a `:checks` axis on one CLI and not
the other.

WHAT IT REFUSES TO DO. A target whose face does not exist yet is reported as a
skipped row, exactly as `bin/check-ports` skips the JVM when there is no javac.
A target whose face DOES exist and disagrees is a failure. The distinction
matters: "not built" and "built wrong" must not look alike, which is the trap
`bin/check` fell into once already when a CLR build silently routed to
"skipped" for long enough that zero CLR rows ran and nobody noticed.

Coverage is reported, not just findings. A checker that says "0 problems" has
told you nothing about what it looked at (see `check-vocab-used` and
`port-survey --coverage` for the same discipline).
"""

import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Where each target's face lives, and whether it is BUILT.
#
# BOTH DIRECTIONS FAIL, and that rule is why this table has a flag rather than
# just a path. `built: False` with a file that exists means a face landed and this
# gate passed over it; `built: True` with no file means the table claims something
# that is not there.
#
# THE FIRST OF THOSE ACTUALLY HAPPENED HERE. This guessed the JVM face was
# `rt/Artifact.java`; the real one is `rt/Sandbox.java`. The gate printed
# `no face ..., skipping` and exited 0 while a whole JVM face went unchecked. A
# GUESSED PATH IS THE HAZARD, not a missing one -- absence and wrongness look
# identical from here, and the flag is the only thing that separates them.
#
# IT HAPPENED A THIRD TIME with wasm, whose path was `runtime/src/fourops.rs`.
# The bridge machinery -- the system port, resume, drain -- lives in the CONC
# UNIT, so the face adapting it does too, and the unit is on every link line
# unconditionally (`calls-are-ports`). Three guessed paths in one small table is
# the argument for the flag rather than against the table.
FACES = {
    'clr':  {'path': 'runtimes/clr/src/rt/Artifact.cs',            'built': True},
    'jvm':  {'path': 'runtimes/jvm/src/com/flint/rt/Sandbox.java', 'built': True},
    'wasm': {'path': 'units-src/flint-conc/src/lib.rs',            'built': True},
}


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.exists(p):
        return None
    with open(p, encoding='utf-8') as f:
        return f.read()


# ---------------------------------------------------------------- tiny edn read
#
# Enough EDN for this one file, and NOT a general reader: keywords, strings,
# integers, vectors, maps, and `;` comments. A general reader is `flint.reader`'s
# job and pulling babashka in here would make a python gate depend on a jvm.

class Edn:
    def __init__(self, s):
        self.s, self.i = s, 0

    def ws(self):
        while self.i < len(self.s):
            c = self.s[self.i]
            if c == ';':
                while self.i < len(self.s) and self.s[self.i] != '\n':
                    self.i += 1
            elif c in ' \t\r\n,':
                self.i += 1
            else:
                return

    def read(self):
        self.ws()
        c = self.s[self.i]
        if c == '{':
            self.i += 1
            out = {}
            while True:
                self.ws()
                if self.s[self.i] == '}':
                    self.i += 1
                    return out
                k = self.read()
                v = self.read()
                out[k] = v
        if c == '[':
            self.i += 1
            out = []
            while True:
                self.ws()
                if self.s[self.i] == ']':
                    self.i += 1
                    return out
                out.append(self.read())
        if c == '"':
            self.i += 1
            buf = []
            while self.s[self.i] != '"':
                if self.s[self.i] == '\\':
                    self.i += 1
                    buf.append({'n': '\n', 't': '\t'}.get(self.s[self.i], self.s[self.i]))
                else:
                    buf.append(self.s[self.i])
                self.i += 1
            self.i += 1
            # Multi-line strings in this file are wrapped prose; collapse the
            # whitespace so a `:note` can be reflowed without changing meaning.
            return re.sub(r'\s+', ' ', ''.join(buf))
        j = self.i
        while self.i < len(self.s) and self.s[self.i] not in ' \t\r\n,{}[]"':
            self.i += 1
        tok = self.s[j:self.i]
        if re.fullmatch(r'-?\d+', tok):
            return int(tok)
        if tok in ('true', 'false'):
            return tok == 'true'
        if tok == 'nil':
            return None
        return tok  # keywords stay as their literal text, ':version' and so on



# ------------------------------------------------------------------- extractors
#
# One per target face. Each answers the same questions -- which operations are
# declared, which status values -- so the checks below are written once and not
# per target.

def clr_face(txt):
    # STATIC AND INSTANCE BOTH. `Loop` is an instance method now -- `Boot` answers
    # a sandbox and `Loop` is its, because a static `Loop` means one sandbox per
    # load context. A pattern matching only `public static` stopped finding it.
    ops = set()
    for m in re.finditer(r'public\s+(?:static\s+)?[\w\.<>\[\]?]+\s+(\w+)\s*\(', txt):
        ops.add(m.group(1))
    status = {}
    body = re.search(r'enum\s+Status\s*\{(.*?)\}', txt, re.S)
    if body:
        for m in re.finditer(r'(\w+)\s*=\s*(\d+)', body.group(1)):
            status[int(m.group(2))] = m.group(1)
    return {'ops': ops, 'status': status}


def java_face(txt):
    # `static` IS OPTIONAL, and requiring it read `loop` as absent. `boot` is a
    # factory and answers a `Sandbox`, so `loop` and `link` are that object's --
    # INSTANCE methods. That shape is the one `four-operations` settled on,
    # because a static surface means one sandbox per process and cannot carry
    # concurrent `loop`. A gate that demanded `static` was demanding the shape
    # that was rejected.
    ops = set(re.findall(r'public\s+(?:static\s+)?[\w\.<>\[\]]+\s+(\w+)\s*\(', txt))
    status = {}
    # ONE DECLARATION, SEVERAL NAMES: `static final int DONE = 0, THREW = 1,
    # NEEDS_HOST = 2;` is legal Java and common, and anchoring on `static final
    # int` per name found only the first. Split the declaration, then read each
    # `NAME = digits` out of it.
    for decl in re.finditer(r'static\s+final\s+int\s+([^;]+);', txt):
        for m in re.finditer(r'(\w+)\s*=\s*(\d+)', decl.group(1)):
            status[int(m.group(2))] = m.group(1)
    return {'ops': ops, 'status': status}


def rust_face(txt):
    ops = set(re.findall(r'pub\s+(?:extern\s+"C"\s+)?fn\s+(\w+)', txt))
    status = {}
    # BOTH SHAPES. This read only `NAME = n,` -- an enum variant, with the comma
    # required -- so `pub const DONE: i32 = 0;` was invisible and the wasm face
    # was reported as declaring no statuses while declaring all three. Rust
    # spells a small closed set either way and neither is wrong.
    for m in re.finditer(r'(?:pub\s+)?const\s+(\w+)\s*:\s*\w+\s*=\s*(\d+)\s*;', txt):
        status[int(m.group(2))] = m.group(1)
    for m in re.finditer(r'(\w+)\s*=\s*(\d+)\s*,', txt):
        status.setdefault(int(m.group(2)), m.group(1))
    return {'ops': ops, 'status': status}


EXTRACT = {'clr': clr_face, 'jvm': java_face, 'wasm': rust_face}


def spelling(d, target):
    """A per-target value out of a contract map, accepting either key form."""
    return d.get(':' + target, d.get(target))


def main():
    contract = Edn(read('runtimes/artifact-ops/contract.edn')).read()
    ops = contract[':operations']
    gone = contract[':removed-operations']
    want_status = {int(k): v for k, v in contract[':status'].items()}
    rules = contract[':rules']
    metadata = contract[':metadata']

    fails, rows, looked = [], [], 0
    present = []

    for target in sorted(FACES):
        spec = FACES[target]
        txt = read(spec['path'])
        # The two-way rule. Neither of these may look like the other.
        if txt is None and spec['built']:
            fails.append('%s: the table says this face is BUILT and %s does not exist. '
                         'Either the path is wrong or the flag is.' % (target, spec['path']))
            continue
        if txt is not None and not spec['built']:
            fails.append('%s: %s exists but this table says the face is not built. '
                         'Mark it built, or the check is passing over a real implementation.'
                         % (target, spec['path']))
            continue
        if txt is None:
            rows.append('  --   %-4s no face at %s, not built' % (target, spec['path']))
            continue
        present.append(target)
        face = EXTRACT[target](txt)

        # 1. every operation, by this target's own spelling.
        missing = [o[':op'] for o in ops
                   if spelling(o[':spelling'], target) not in face['ops']]
        looked += len(ops)
        if missing:
            fails.append('%s: does not declare %s' % (target, ', '.join(missing)))

        # 2. and NONE of the removed ones. A face that grows `prop` back would
        #    otherwise read as an addition rather than the regression it is.
        looked += len(gone)
        for g in gone:
            sp = spelling(g[':forbidden-spelling'], target)
            if sp and sp in face['ops']:
                fails.append('%s: declares `%s`, which was REMOVED from the contract. %s'
                             % (target, sp, g[':why'].split('.')[0]))

        # 3. THE STATUS NUMBERS, and only the numbers.
        #
        # The CLR spells them `enum Status { Done, Threw, NeedsHost }` and the JVM
        # spells them `static final int DONE, THREW, NEEDS_HOST`. That is NOT a
        # divergence: an enum is how .NET spells three named constants, and forcing
        # either language into the other's spelling would be the mistake. So the
        # contract records the per-target spelling descriptively and this asserts
        # the SET OF VALUES, which is what the ABI actually is.
        looked += len(want_status)
        # THE NUMBERS ARE THE ABI, NOT THE SPELLING. This compared the whole
        # name-to-number map and so demanded that every language spell the
        # statuses the way one of them does: the jvm writes `DONE`/`THREW`/
        # `NEEDS_HOST`, the clr an `enum Status { Done, Threw, NeedsHost }`, and
        # both are idiomatic where they live. `four-operations` says the numbers
        # are what this project treats as the ABI, so those are what must agree.
        #
        # A face is still required to DECLARE them rather than only document
        # them: a comment saying "0, 1, 2" beside a function is exactly the prose
        # this project has watched fail to bind the code next to it.
        if not face['status']:
            fails.append('%s: declares no status values at all -- the numbers must be '
                         'in the SOURCE, not only in a comment' % target)
        elif sorted(face['status'].keys()) != sorted(want_status.keys()):
            fails.append('%s: status NUMBERS are %r, the contract says %r (names may differ '
                         'per host; the numbers are the ABI)'
                         % (target, sorted(face['status'].keys()),
                            sorted(want_status.keys())))

        # 4. the rules, as evidence in the source.
        for r in rules:
            ev = spelling(r[':evidence'], target)
            if not ev:
                continue
            looked += 1
            src = read(ev[':file'])
            if src is None:
                fails.append('%s: rule evidence file missing: %s' % (target, ev[':file']))
                continue
            if ':must-contain' in ev and ev[':must-contain'] not in src:
                fails.append('%s: %s -- %s does not say %r'
                             % (target, r[':rule'], ev[':file'], ev[':must-contain']))
            if ':must-not-match' in ev and re.search(ev[':must-not-match'], src):
                fails.append('%s: %s -- %s matches %r, which it must not'
                             % (target, r[':rule'], ev[':file'], ev[':must-not-match']))

        rows.append('  ok   %-4s %d operations, %d refused, %d status numbers, %d rules'
                    % (target, len(ops), len(gone), len(want_status),
                       sum(1 for r in rules if spelling(r[':evidence'], target))))

    # ---- metadata carriers. NOT an operation, and the namespacing is the part
    # that goes wrong quietly: all three are flat namespaces, and on the JVM an
    # unrecognised attribute is silently ignored by specification.
    dot = metadata[':names-must-contain']
    for target, carrier in sorted(metadata[':carriers'].items()):
        t = target.lstrip(':')
        looked += 1
        if dot not in carrier[':name']:
            fails.append('metadata: the %s carrier is named %r, which has no %r in it -- '
                         'these are flat namespaces and a bare name is one anyone could pick'
                         % (t, carrier[':name'], dot))
        ev = carrier.get(':evidence')
        if ev:
            looked += 1
            src = read(ev[':file'])
            if src is None:
                fails.append('metadata: %s carrier evidence file missing: %s' % (t, ev[':file']))
            elif ev[':must-contain'] not in src:
                fails.append('metadata: %s -- %s does not say %r'
                             % (t, ev[':file'], ev[':must-contain']))

    # The fact this contract shares with a gate that already exists, checked here
    # so the two cannot drift silently.
    meta = read('meta.edn')
    ver = re.search(r':version\s+"([^"]+)"', meta).group(1)

    for r in rows:
        print(r)
    print('check-artifact-ops: %d assertions, %d metadata carriers, %d target faces (%s), version %s'
          % (looked, len(metadata[':carriers']),
             len(present), ', '.join(present) or 'none', ver))
    if fails:
        for f in fails:
            print('  FAIL ' + f)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
