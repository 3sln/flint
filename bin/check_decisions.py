import os, re, sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR  = os.path.join(HERE, 'doc', 'decisions')

# Order matters: the longer phrase has to win over the substring it contains.
BANNER_RULES = [
    ('SUPERSEDED',    'superseded'),
    ('SHELVED',       'shelved'),
    ('NOT BUILT',     'roadmap'),
    ('PARTLY BUILT',  'partial'),
    ('PART 1 IS BUILT','partial'),
    ('LIVE',          'live'),
    ('BUILT',         'shipped'),
    ('DONE',          'shipped'),
]
ROW_RULES = [
    ('superseded',     'superseded'),
    ('SHELVED',        'shelved'),
    ('Partly shipped', 'partial'),
    ('Part 1 shipped', 'partial'),
    ('Roadmap',        'roadmap'),
    ('Live',           'live'),
    ('Shipped',        'shipped'),
    ('Done',           'shipped'),
]

def classify(text, rules, where):
    hits = [cls for kw, cls in rules if kw in text]
    if not hits:
        return None, "%s: no status keyword found in %r" % (where, text[:90])
    return hits[0], None

def banner_of(path):
    """The first blockquote in the first 14 lines, joined."""
    lines = open(path, encoding='utf-8').read().split('\n')[:14]
    quote = []
    for ln in lines:
        if ln.startswith('>'):
            quote.append(ln.lstrip('> ').rstrip())
        elif quote:
            break
    return ' '.join(quote)

def main():
    errs = []
    readme = open(os.path.join(DIR, 'README.md'), encoding='utf-8').read()

    rows = {}
    for m in re.finditer(r'^\| \[(\d{4})\]\(([^)]+)\) \| [^|]* \| (.*?) \|$',
                         readme, re.M):
        rows[m.group(1)] = (m.group(2), m.group(3))

    docs = sorted(f for f in os.listdir(DIR)
                  if f.endswith('.md') and f != 'README.md')
    if not docs:
        print('check-decisions: FAIL — no decision docs found', file=sys.stderr)
        return 1

    for f in docs:
        num = f[:4]
        if num not in rows:
            errs.append('%s has no row in the index' % f)
            continue
        link, status = rows[num]
        if link != f:
            errs.append('%s: the index links to %s' % (f, link))
        b = banner_of(os.path.join(DIR, f))
        if not b:
            errs.append('%s: no status banner in the first 14 lines. Every doc '
                        'says what it is at the top.' % f)
            continue
        bcls, e1 = classify(b, BANNER_RULES, f + ' banner')
        # Only the row's LEADING bold run is its status; the prose after it
        # often names a supersession that applies to one section, not the doc.
        lead = re.match(r'\*\*(.+?)\*\*', status)
        if not lead:
            errs.append('%s: the index row does not start with a bold status'
                        % f)
            continue
        rcls, e2 = classify(lead.group(1), ROW_RULES, f + ' index row')
        for e in (e1, e2):
            if e: errs.append(e)
        if bcls and rcls and bcls != rcls:
            errs.append('%s: banner says %s, the index says %s'
                        % (f, bcls.upper(), rcls.upper()))

    for num in sorted(rows):
        if not os.path.exists(os.path.join(DIR, rows[num][0])):
            errs.append('the index has a row for %s, which does not exist'
                        % rows[num][0])

    if errs:
        print('check-decisions: %d disagreement(s)' % len(errs), file=sys.stderr)
        for e in errs:
            print('  ' + e, file=sys.stderr)
        return 1
    print('decisions: %d docs, every banner agrees with its index row' % len(docs))
    return 0

sys.exit(main())
