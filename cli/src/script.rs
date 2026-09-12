//! Reading a standalone `#!` script's `ns` form (`DECISIONS.md#standalone-scripts`).
//!
//! A script is ONE FILE that carries its own configuration, so the launcher has
//! to learn three things before it can compile anything: which namespace the
//! file is, whether it claims to be a script and what it runs, and which
//! directories -- if any -- it wants beside itself on the source path.
//!
//! Read with a SCAN and not with an EDN parser, the way `read_workspace` reads
//! `deps.edn` on this side: the guest owns the format, and a second full reader
//! of it is a second thing to keep true. What this needs is a name and two
//! collections, and it hands the file to the real reader immediately after.

/// The source with strings, character literals and `;` comments blanked out,
/// so a scan cannot be fooled by a `(ns` inside a docstring.
///
/// Blanked rather than deleted: every offset stays where it was, which keeps
/// this honest about what it is looking at.
fn code_only(src: &str) -> String {
    let b: Vec<char> = src.chars().collect();
    let mut out = String::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if c == ';' {
            while i < b.len() && b[i] != '\n' {
                out.push(' ');
                i += 1;
            }
        } else if c == '"' {
            out.push(' ');
            i += 1;
            while i < b.len() {
                let d = b[i];
                if d == '\\' {
                    out.push(' ');
                    i += 1;
                    if i < b.len() {
                        out.push(if b[i] == '\n' { '\n' } else { ' ' });
                        i += 1;
                    }
                    continue;
                }
                out.push(if d == '\n' { '\n' } else { ' ' });
                i += 1;
                if d == '"' {
                    break;
                }
            }
        } else if c == '\\' && i + 1 < b.len() {
            // A character literal -- `\"` and `\;` would otherwise open a
            // string and a comment that are not there.
            out.push(' ');
            out.push(if b[i + 1] == '\n' { '\n' } else { ' ' });
            i += 2;
        } else {
            out.push(c);
            i += 1;
        }
    }
    out
}

fn is_ws(c: char) -> bool {
    c.is_whitespace() || c == ','
}

/// The text of the form opening at `at`, brackets balanced.
fn form_at(s: &[char], at: usize) -> String {
    let mut depth = 0i32;
    let mut out = String::new();
    for &c in s.iter().skip(at) {
        out.push(c);
        match c {
            '(' | '[' | '{' => depth += 1,
            ')' | ']' | '}' => {
                depth -= 1;
                if depth == 0 {
                    return out;
                }
            }
            _ => {}
        }
    }
    out
}

/// What a script's `ns` form says about itself.
pub struct ScriptNs {
    pub ns: String,
    /// `ns/fn`, present only when the `ns` is marked `^:script`.
    pub entry: Option<String>,
    /// Directories the script asks for BESIDE itself, relative to the script.
    pub paths: Vec<String>,
    /// Whether it declares dependencies at all.
    pub has_deps: bool,
}

/// One metadata datum after a `^`, as the text it spans, and where it ends.
fn meta_at(s: &[char], mut i: usize) -> (String, usize) {
    let mut out = String::new();
    if i < s.len() && s[i] == '{' {
        let t = form_at(s, i);
        i += t.chars().count();
        return (t, i);
    }
    while i < s.len() && !is_ws(s[i]) && s[i] != '(' && s[i] != ')' {
        out.push(s[i]);
        i += 1;
    }
    (out, i)
}

/// The bare token following `key` inside `text`, or empty.
fn token_after(text: &str, key: &str) -> String {
    let Some(at) = text.find(key) else {
        return String::new();
    };
    text[at + key.len()..]
        .split_whitespace()
        .next()
        .unwrap_or("")
        .trim_end_matches('}')
        .to_string()
}

/// Read the `ns` form of `src`, or nothing when there is not one.
pub fn read_ns(src: &str) -> Option<ScriptNs> {
    let text = code_only(src);
    let s: Vec<char> = text.chars().collect();
    // The first `(ns` with whitespace after it. A scan, so `(nsfoo` and
    // `(ns-bar` do not match; anything subtler than that is the real reader's
    // to complain about, one step later.
    let mut at = None;
    for i in 0..s.len().saturating_sub(3) {
        if s[i] == '(' && s[i + 1] == 'n' && s[i + 2] == 's' && is_ws(s[i + 3]) {
            at = Some(i);
            break;
        }
    }
    let at = at?;
    // THE FORM IS CUT FROM THE ORIGINAL and not from the blanked copy, which is
    // why `code_only` blanks rather than deletes: every offset still lines up,
    // so a scan can locate in the safe text and READ in the real one. Reading
    // from the blanked copy is what the first version did, and `(:paths
    // ["side"])` came back as `(:paths [      ])` -- an empty source path, and
    // a script that named a directory and did not get it.
    let orig: Vec<char> = src.chars().collect();
    let form = form_at(&orig, at);
    let mut i = at + 3;
    let mut script: Option<String> = None;
    loop {
        while i < s.len() && is_ws(s[i]) {
            i += 1;
        }
        if i < s.len() && s[i] == '^' {
            let (m, next) = meta_at(&s, i + 1);
            i = next;
            // `^:script` is the flag; `^{:script go}` names the entry. Both,
            // because the flag is what everyone writes and the map is what a
            // file whose entry is not called `main` needs.
            if m == ":script" {
                script = Some("main".to_string());
            } else if m.starts_with('{') && m.contains(":script") {
                let t = token_after(&m, ":script");
                if t == "true" || t.is_empty() {
                    script = Some("main".to_string());
                } else {
                    script = Some(t);
                }
            }
            continue;
        }
        break;
    }
    let mut ns = String::new();
    while i < s.len() && !is_ws(s[i]) && s[i] != '(' && s[i] != ')' {
        ns.push(s[i]);
        i += 1;
    }
    if ns.is_empty() {
        return None;
    }
    let paths = crate::edn_block(&form, ":paths", '[', ']')
        .split_whitespace()
        .map(|t| t.trim_matches('"').to_string())
        .filter(|t| !t.is_empty())
        .collect();
    let has_deps = !crate::edn_block(&form, ":deps", '{', '}').trim().is_empty();
    let entry = script.map(|f| format!("{ns}/{f}"));
    Some(ScriptNs { ns, entry, paths, has_deps })
}

/// The path a namespace's source is FOUND at, which is not the path it sits at
/// on disk.
///
/// A script is `~/bin/greet` or `./do-the-thing.fln`; the compiler looks a
/// namespace up by `flint.project/ns->path`, so a file handed over BY NAME has
/// to be keyed by the namespace it declares or it is simply not there. Keying
/// by filename is what this used to do, and it worked only where the two
/// already agreed.
pub fn ns_key(ns: &str, ext: &str) -> String {
    let mut out = String::new();
    for c in ns.chars() {
        out.push(match c {
            '-' => '_',
            '.' => '/',
            other => other,
        });
    }
    format!("{out}{ext}")
}
