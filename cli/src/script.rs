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
use std::fs;
use std::path::Path;
use anyhow::{bail, Result};

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
    /// What the script asks to be lent, from `:capabilities` in its `:script`
    /// map. A REQUEST, not a grant: the script says what it needs, and the
    /// person running it decides. A script that asks is one whose demands can
    /// be read before it runs, which is the opposite of one that dies halfway
    /// through for want of `:fs`.
    pub capabilities: Vec<String>,
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
    // The `:script` map's own text, when the map form was used -- `:deps`,
    // `:paths` and `:capabilities` are read out of THIS rather than out of the
    // whole `ns` form, so a non-script namespace cannot accidentally supply
    // them and a script's are scoped to where they were declared.
    let mut script_block = String::new();
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
            // FOUR SPELLINGS, one idea at four levels of detail, matching
            // `flint.analyzer/script-spec`:
            //
            //     ^:script                   entry is ns/main
            //     ^{:script go}              entry is ns/go
            //     ^{:script {:entry go}}     the same, longhand
            //     ^{:script {:entry go :deps {..} :paths [..] :capabilities [..]}}
            //
            // Everything a script declares lives in the `ns` METADATA rather
            // than in `ns` clauses, so it stays inside a shape every Clojure
            // reader already parses.
            if m == ":script" {
                script = Some("main".to_string());
            } else if m.starts_with('{') && m.contains(":script") {
                let inner = crate::edn_block(&m, ":script", '{', '}');
                if !inner.is_empty() {
                    // The map form. `:entry` names it; absent means `main`.
                    let e = token_after(&inner, ":entry");
                    script = Some(if e.is_empty() { "main".to_string() } else { e });
                    script_block = inner;
                } else {
                    let t = token_after(&m, ":script");
                    script = Some(if t == "true" || t.is_empty() {
                        "main".to_string()
                    } else {
                        t
                    });
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
    // THE ATTR-MAP, which is the other place Clojure puts this and the one a
    // multi-line declaration will use: `(ns foo {:script {:entry go ..}})`.
    // Looked for only when the `^` metadata did not already supply it, so the
    // two cannot disagree silently -- and read from the FORM, since by
    // definition it sits after the name rather than before it.
    if script.is_none() && form.contains(":script") {
        // PRESENCE, not contents. `edn_block` answers "" both for a key that is
        // absent and for one whose map is empty, so `{:script {}}` -- a script
        // that declares nothing but that it IS one -- read as not a script at
        // all. The key being there is what marks the file.
        let attr = crate::edn_block(&form, ":script", '{', '}');
        let e = token_after(&attr, ":entry");
        script = Some(if e.is_empty() { "main".to_string() } else { e });
        script_block = attr;
    }
    let scope = if script_block.is_empty() { form.clone() } else { script_block.clone() };
    let paths = crate::edn_block(&scope, ":paths", '[', ']')
        .split_whitespace()
        .map(|t| t.trim_matches('"').to_string())
        .filter(|t| !t.is_empty())
        .collect();
    let has_deps = !crate::edn_block(&scope, ":deps", '{', '}').trim().is_empty();
    let capabilities = crate::edn_block(&scope, ":capabilities", '[', ']')
        .split_whitespace()
        .map(|t| t.trim_start_matches(':').to_string())
        .filter(|t| !t.is_empty())
        .collect();
    let entry = script.map(|f| format!("{ns}/{f}"));
    Some(ScriptNs { ns, entry, paths, has_deps, capabilities })
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

// ------------------------------------------------------- asking for consent

/// Where remembered answers live: one line per script, `<sha256> <caps>`.
///
/// Under the USER's home rather than the project, because the answer is the
/// user's and not the checkout's -- a shared machine must not let one account's
/// "always" speak for another's.
fn grants_file() -> Option<std::path::PathBuf> {
    std::env::var_os("HOME").map(|h| std::path::Path::new(&h).join(".flint").join("script-grants"))
}

/// What this user has already said `always` to, for this exact content.
fn remembered(hash: &str) -> Option<Vec<String>> {
    let f = grants_file()?;
    let text = fs::read_to_string(f).ok()?;
    for line in text.lines() {
        let mut it = line.split_whitespace();
        if it.next() == Some(hash) {
            return Some(it.map(|s| s.to_string()).collect());
        }
    }
    None
}

fn remember(hash: &str, caps: &[String]) -> Result<()> {
    let Some(f) = grants_file() else { return Ok(()) };
    if let Some(d) = f.parent() {
        fs::create_dir_all(d)?;
    }
    let mut text = fs::read_to_string(&f).unwrap_or_default();
    if !text.is_empty() && !text.ends_with('\n') {
        text.push('\n');
    }
    text.push_str(hash);
    for c in caps {
        text.push(' ');
        text.push_str(c);
    }
    text.push('\n');
    fs::write(&f, text)?;
    Ok(())
}

/// Ask, once, and let the answer be remembered.
///
/// KEYED BY CONTENT, not by path. A remembered answer is about the code the
/// user read, so editing the script asks again -- which is the whole point: a
/// grant that survived an edit would be a grant to code nobody agreed to.
///
/// REFUSES WHEN THERE IS NOBODY TO ASK. With no terminal -- a pipe, CI, a cron
/// job -- there is no consent to be had, and the safe direction is to run with
/// nothing rather than to assume yes. `:with` on the command line still works
/// there, which is the explicit way to say it.
pub fn consent(path: &Path, asked: &[String], hash: &str) -> Result<Vec<String>> {
    if asked.is_empty() {
        return Ok(vec![]);
    }
    if let Some(ok) = remembered(hash) {
        return Ok(ok);
    }
    let pretty: Vec<String> = asked.iter().map(|c| format!(":{c}")).collect();
    if !std::io::IsTerminal::is_terminal(&std::io::stdin()) {
        // `:with` BEFORE the path, because everything after it belongs to the
        // script -- a script has to be able to receive `:with` as an argument
        // of its own. The first version of this message had the order wrong,
        // which would have sent people to an invocation that silently passes
        // the flag through to their program.
        bail!("{} asks for {} and there is no terminal to ask on.\n\
               Run it where you can answer, or lend them explicitly with \
               `flint :with [{}] {}`.",
              path.display(), pretty.join(" "), asked.join(" "), path.display());
    }
    eprintln!();
    eprintln!("  {} asks to be lent {}", path.display(), pretty.join(" "));
    eprintln!("  {}", "-".repeat(60));
    eprint!("  allow? [y]es once, [a]lways for this exact file, [N]o: ");
    use std::io::Write as _;
    std::io::stderr().flush().ok();
    let mut line = String::new();
    std::io::stdin().read_line(&mut line)?;
    match line.trim().to_ascii_lowercase().as_str() {
        "y" | "yes" => Ok(asked.to_vec()),
        "a" | "always" => {
            remember(hash, asked)?;
            eprintln!("  remembered; editing the file will ask again");
            Ok(asked.to_vec())
        }
        _ => Ok(vec![]),
    }
}
