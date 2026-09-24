#!/usr/bin/env python3
"""The four operations exist, and mean the same thing, on every target.

`runtimes/four-ops/contract.edn` is the contract as data and
`DECISIONS.md#four-operations` is the prose. This checks each target face
against the data.

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

# Where each target's face lives. A target absent from this map has no face yet;
# one present whose file is missing is a skipped row.
FACES = {
    'clr': 'runtimes/clr/src/rt/Artifact.cs',
    'jvm': 'runtimes/jvm/src/com/flint/rt/Artifact.java',
    'wasm': 'runtime/src/fourops.rs',
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
# One per target face. Each answers the same three questions -- which operations
# are declared, which status values, which prop keys -- so the checks below are
# written once and not per target.

def clr_face(txt):
    ops = set()
    for m in re.finditer(r'public\s+static\s+[\w\.<>\[\]?]+\s+(\w+)\s*\(', txt):
        ops.add(m.group(1))
    status = {}
    body = re.search(r'enum\s+Status\s*\{(.*?)\}', txt, re.S)
    if body:
        for m in re.finditer(r'(\w+)\s*=\s*(\d+)', body.group(1)):
            status[int(m.group(2))] = m.group(1)
    # The keys `Prop`'s switch handles. Anchored on the switch so an unrelated
    # `case "x":` elsewhere in the file is not counted as a property.
    props = set()
    sw = re.search(r'int\s+Prop\s*\([^)]*\)\s*\{(.*?)\n    \}', txt, re.S)
    if sw:
        props = set(re.findall(r'case\s+"([^"]+)"\s*:', sw.group(1)))
    return {'ops': ops, 'status': status, 'props': props}


def java_face(txt):
    ops = set(re.findall(r'public\s+static\s+[\w\.<>\[\]]+\s+(\w+)\s*\(', txt))
    status = {}
    for m in re.finditer(r'(?:public\s+)?static\s+final\s+int\s+(\w+)\s*=\s*(\d+)', txt):
        status[int(m.group(2))] = m.group(1)
    props = set(re.findall(r'case\s+"([^"]+)"\s*(?:->|:)', txt))
    return {'ops': ops, 'status': status, 'props': props}


def rust_face(txt):
    ops = set(re.findall(r'pub\s+(?:extern\s+"C"\s+)?fn\s+(\w+)', txt))
    status = {}
    for m in re.finditer(r'(\w+)\s*=\s*(\d+)\s*,', txt):
        status[int(m.group(2))] = m.group(1)
    props = set(re.findall(r'"([^"]+)"\s*=>', txt))
    return {'ops': ops, 'status': status, 'props': props}


EXTRACT = {'clr': clr_face, 'jvm': java_face, 'wasm': rust_face}


def main():
    contract = Edn(read('runtimes/four-ops/contract.edn')).read()
    ops = contract[':operations']
    want_status = {int(k): v for k, v in contract[':status'].items()}
    props = contract[':props']
    rules = contract[':rules']

    fails, rows, looked = [], [], 0

    for target in sorted(FACES):
        txt = read(FACES[target])
        if txt is None:
            rows.append('  --   %-4s no face at %s, skipping' % (target, FACES[target]))
            continue
        face = EXTRACT[target](txt)

        # 1. all four operations, by this target's own spelling.
        missing = [o[':op'] for o in ops
                   if o[':spelling'].get(':' + target, o[':spelling'].get(target)) not in face['ops']]
        looked += len(ops)
        if missing:
            fails.append('%s: does not declare %s' % (target, ', '.join(missing)))

        # 2. the status numbers. Names may differ per host; values may not.
        looked += len(want_status)
        if face['status'] and face['status'] != want_status:
            fails.append('%s: status values are %r, the contract says %r'
                         % (target, face['status'], want_status))
        elif not face['status']:
            fails.append('%s: declares no status values at all' % target)

        # 3. prop keys, BOTH DIRECTIONS. A key in the contract that no face
        #    answers is a hole; a key a face answers that the contract does not
        #    list is drift, and the second is the one a one-way check misses.
        want_keys = {p[':key'] for p in props}
        looked += len(want_keys) + len(face['props'])
        for k in sorted(want_keys - face['props']):
            fails.append('%s: prop "%s" is in the contract and not handled' % (target, k))
        for k in sorted(face['props'] - want_keys):
            fails.append('%s: prop "%s" is handled and not in the contract' % (target, k))

        # 4. the rules, as evidence in the source.
        for r in rules:
            ev = r[':evidence'].get(':' + target)
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

        rows.append('  ok   %-4s four operations, %d status values, %d props, %d rules'
                    % (target, len(want_status), len(face['props']),
                       sum(1 for r in rules if (':' + target) in r[':evidence'])))

    # The two facts the contract shares with gates that already exist. Checked
    # here so the contract cannot drift from them silently.
    meta = read('meta.edn')
    ver = re.search(r':version\s+"([^"]+)"', meta).group(1)
    looked += 1
    vprop = next(p for p in props if p[':key'] == 'version')
    if vprop[':expect'].get(':from') != 'meta.edn':
        fails.append('contract: prop "version" must come from meta.edn')
    img = read('runtimes/clr/src/rt/Img.cs')
    looked += 1
    if img and not re.search(r'public\s+const\s+int\s+Version\s*=', img):
        fails.append('contract: Img.cs no longer declares `Version`, which prop '
                     '"image-version" is specified against')

    for r in rows:
        print(r)
    print('check-four-ops: %d assertions over %d target faces (%s), version %s'
          % (looked, sum(1 for t in FACES if read(FACES[t]) is not None),
             ', '.join(sorted(t for t in FACES if read(FACES[t]) is not None)), ver))
    if fails:
        for f in fails:
            print('  FAIL ' + f)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
