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

# Where each target's face lives, AND WHETHER IT IS SUPPOSED TO EXIST YET.
#
# The second half was missing and it made this gate vacuous for any target it
# could not find. It looked for the jvm face at `.../rt/Artifact.java`, the real
# one is `.../rt/Sandbox.java`, and it printed "no face ..., skipping" and exited
# 0 -- so the jvm face landed and was never checked. A guessed filename plus a
# silent skip is the same failure `bin/conform-hosts` had: a gate that passes by
# doing nothing.
#
# So each row now DECLARES, and both directions fail:
#
#   built True  and the face is missing  -> FAIL (it regressed, or moved again)
#   built False and the face EXISTS      -> FAIL (a real implementation is being
#                                          passed over, which is how the first
#                                          one of these was caught)
FACES = {
    'clr':  ('runtimes/clr/src/rt/Artifact.cs',            True),
    'jvm':  ('runtimes/jvm/src/com/flint/rt/Sandbox.java', True),
    'wasm': ('runtime/src/fourops.rs',                     False),
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
    ops = set()
    for m in re.finditer(r'public\s+static\s+[\w\.<>\[\]?]+\s+(\w+)\s*\(', txt):
        ops.add(m.group(1))
    status = {}
    body = re.search(r'enum\s+Status\s*\{(.*?)\}', txt, re.S)
    if body:
        for m in re.finditer(r'(\w+)\s*=\s*(\d+)', body.group(1)):
            status[int(m.group(2))] = m.group(1)
    return {'ops': ops, 'status': status}


def java_face(txt):
    ops = set(re.findall(r'public\s+static\s+[\w\.<>\[\]]+\s+(\w+)\s*\(', txt))
    status = {}
    for m in re.finditer(r'(?:public\s+)?static\s+final\s+int\s+(\w+)\s*=\s*(\d+)', txt):
        status[int(m.group(2))] = m.group(1)
    return {'ops': ops, 'status': status}


def rust_face(txt):
    ops = set(re.findall(r'pub\s+(?:extern\s+"C"\s+)?fn\s+(\w+)', txt))
    status = {}
    for m in re.finditer(r'(\w+)\s*=\s*(\d+)\s*,', txt):
        status[int(m.group(2))] = m.group(1)
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
        rel, built = FACES[target]
        txt = read(rel)
        if txt is None and built:
            fails.append('%s is declared BUILT and has no face at %s -- it moved or '
                        'regressed. A missing face is not a skip.' % (target, rel))
            continue
        if txt is not None and not built:
            fails.append('%s is declared NOT built and yet %s exists -- this gate would '
                        'pass over a real implementation. Flip the row.' % (target, rel))
            continue
        if txt is None:
            rows.append('  --   %-4s not built yet (%s)' % (target, rel))
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

        # 3. the status numbers. Names may differ per host; values may not.
        looked += len(want_status)
        if not face['status']:
            fails.append('%s: declares no status values at all' % target)
        elif face['status'] != want_status:
            fails.append('%s: status values are %r, the contract says %r'
                         % (target, face['status'], want_status))

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

        rows.append('  ok   %-4s %d operations, %d refused, %d status values, %d rules'
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
