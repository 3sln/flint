// EDN, written rather than depended on -- the same fifteen lines `cli/src/main.rs`
// writes, for the same reason.
//
// The spec handed to the compiler is a map of strings to strings and a set of
// strings. This side has to produce the SAME TEXT the native CLI produces, or
// the two CLIs compile the same project into different bytes, so every helper
// here is a transliteration of the Rust one rather than an idiomatic rewrite.

/// `edn_string` in `cli/src/main.rs`. Five escapes and nothing else: a control
/// character that is not one of them travels through literally, which is what
/// the Rust does, and a "better" escaper here would change the spec text.
export function ednString(s) {
  let out = '"';
  for (const c of String(s)) {
    if (c === '"') out += '\\"';
    else if (c === '\\') out += '\\\\';
    else if (c === '\n') out += '\\n';
    else if (c === '\r') out += '\\r';
    else if (c === '\t') out += '\\t';
    else out += c;
  }
  return out + '"';
}

/// Order by UTF-8 BYTES, which is what Rust's `BTreeMap<String, _>` does.
///
/// JavaScript's default sort orders by UTF-16 code unit, and the two disagree
/// above U+FFFF -- an astral character sorts before U+E000 by code unit and
/// after it by byte. Every path and builtin name here is ASCII today, so this
/// is a difference nobody would ever see; it is written anyway because the
/// whole point of this file is producing the same text as the Rust, and a
/// sorting rule that is right by accident is one that stops being right
/// without anybody touching it.
export function byBytes(a, b) {
  const x = Buffer.from(a, 'utf8');
  const y = Buffer.from(b, 'utf8');
  return Buffer.compare(x, y);
}

/// The text between the delimiters of the collection following `key`.
/// `edn_block` in `cli/src/main.rs`.
export function ednBlock(text, key, open, close) {
  const at = text.indexOf(key);
  if (at < 0) return '';
  const rest = text.slice(at + key.length);
  const o = rest.indexOf(open);
  if (o < 0) return '';
  let depth = 0;
  for (let i = 0; i < rest.length - o; i++) {
    const c = rest[o + i];
    if (c === open) depth += 1;
    else if (c === close) {
      depth -= 1;
      if (depth === 0) return rest.slice(o + 1, o + i).trim();
    }
  }
  return '';
}

/// The bare token following `key`. `edn_token` in `cli/src/main.rs`.
export function ednToken(text, key) {
  const at = text.indexOf(key);
  if (at < 0) return '';
  const tok = text.slice(at + key.length).split(/\s+/).filter((s) => s !== '')[0] ?? '';
  return tok.replace(/\}+$/, '');
}
