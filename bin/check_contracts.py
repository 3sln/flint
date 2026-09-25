"""The overlapping facts in the two artifact contracts agree.

A REGEX AND NOT AN EDN READER, deliberately: this runs before anything is built
and must not need babashka, and the two facts compared -- a set of operation
names and a map of status numbers -- are small enough to read without one.

The cost is that it must REFUSE when it cannot find them, or a pattern that
stopped matching would read as agreement. Both files are checked for a non-empty
result before anything is compared.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STRUCT = 'runtimes/artifact-ops/contract.edn'
BEHAVE = 'test/four-ops/contract.edn'


def text(rel):
    with open(os.path.join(ROOT, rel), encoding='utf-8') as f:
        return f.read()


def ops_in(txt, key):
    """Operation names under one top-level key, and NOT under any other.

    THE FIRST VERSION SCANNED THE WHOLE FILE and reported four operations against
    the other file's three, calling it a disagreement between the contracts. It
    was a disagreement between my regex and the file: `:op "prop"` is there under
    `:removed-operations`. So the scan is bounded to the key asked for, by cutting
    at the next top-level key -- one leading space, in both files' layout.
    """
    m = re.search(r'^ :' + key + r'\b(.*?)(?=^ :[a-z-]+)', txt, re.S | re.M)
    body = m.group(1) if m else ''
    # TWO SHAPES, because the two files chose differently and both are reasonable:
    # the structural one is a vector of maps each with an `:op`, the behavioural
    # one a plain vector of strings. Reading only the first reported "found no
    # operation names", which this script is built to treat as a refusal rather
    # than an agreement -- so the miss was loud, which is the whole point.
    names = set(re.findall(r':op\s+"([a-z-]+)"', body))
    if names:
        return names
    vec = re.match(r'\s*\[(.*?)\]', body, re.S)
    return set(re.findall(r'"([a-z-]+)"', vec.group(1))) if vec else set()


def statuses(txt):
    """`{0 "Done" 1 "Threw" 2 "NeedsHost"}`, however it is laid out.

    The VALUE is truncated at its first word: one file carries a paragraph of
    explanation inside the string and the other a bare name, and that is not a
    disagreement about the contract.
    """
    m = re.search(r':status\s*\{(.*?)\}', txt, re.S)
    if not m:
        return {}
    out = {}
    for k, v in re.findall(r'(\d+)\s+"([^"]*)"', m.group(1)):
        w = v.split()
        out[int(k)] = w[0].strip('"-') if w else ''
    return out


def main():
    s, b = text(STRUCT), text(BEHAVE)
    so, bo = ops_in(s, 'operations'), ops_in(b, 'operations')
    rm = ops_in(s, 'removed-operations')
    ss, bs = statuses(s), statuses(b)
    bad = []

    # REFUSE RATHER THAN AGREE when a pattern found nothing: an empty set equals
    # an empty set, which is how a broken reader reports success.
    for name, got, rel in (('operation names', so, STRUCT), ('operation names', bo, BEHAVE),
                           ('statuses', ss, STRUCT), ('statuses', bs, BEHAVE)):
        if not got:
            bad.append('found no %s in %s -- the pattern stopped matching, and an '
                       'empty comparison is not an agreement' % (name, rel))

    # `prop` WAS THE FOURTH OPERATION. Its removal is a fact worth asserting: the
    # structural contract forbids it by name, and a behavioural contract that grew
    # it back would otherwise read as extra coverage.
    if 'prop' not in rm:
        bad.append('%s no longer forbids `prop` by name -- it was the fourth operation, '
                   'and a face regrowing it must read as a regression' % STRUCT)
    if rm & bo:
        bad.append('%s exercises %s, which %s lists as REMOVED'
                   % (BEHAVE, sorted(rm & bo), STRUCT))

    if so and bo and so != bo:
        bad.append('operations differ: %s says %s, %s says %s'
                   % (STRUCT, sorted(so), BEHAVE, sorted(bo)))
    if ss and bs:
        if sorted(ss) != sorted(bs):
            bad.append('status NUMBERS differ: %s says %s, %s says %s'
                       % (STRUCT, sorted(ss), BEHAVE, sorted(bs)))
        else:
            for n in sorted(ss):
                if ss[n].lower() != bs[n].lower():
                    bad.append('status %d is %r in %s and %r in %s'
                               % (n, ss[n], STRUCT, bs[n], BEHAVE))

    if bad:
        print('check-contracts: %d disagreement(s) between the two contracts:' % len(bad))
        for x in bad:
            print('  ' + x)
        return 1
    print('check-contracts: the two contracts agree on %d operations (%s), %d statuses '
          '(%s), and %s stays forbidden'
          % (len(so), ', '.join(sorted(so)), len(ss),
             ', '.join('%d=%s' % (n, ss[n]) for n in sorted(ss)), ', '.join(sorted(rm))))
    return 0


sys.exit(main())
