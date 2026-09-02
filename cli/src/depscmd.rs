//! `flint deps` -- the command surface (`doc/decisions/0037`).
//!
//! ```text
//! flint deps add npm:left-pad          resolve latest, pin, write
//! flint deps add git:github.com/org/x
//! flint deps add npm:left-pad@^1.3.0   a constraint, resolved and pinned
//! flint deps tree                      what is reached, and from where
//! flint deps pin                       write every transitive into overrides
//! ```
//!
//! ## Where the logic is
//!
//! Not here. `add` resolves by running `flint.deps.resolve` -- the same `.cljc`
//! plan a build uses -- so the version this writes and the version a build
//! picks cannot disagree. What is in this file is argument parsing and EDN
//! editing, which is the host's half.
//!
//! ## Editing `deps.edn`
//!
//! By TEXT, not by read-and-print. A `deps.edn` is a file somebody wrote:
//! comments, blank lines and key order are theirs, and a tool that reads EDN
//! and prints it back destroys all three. So an insertion finds the `:deps`
//! map and adds a line; everything else in the file is left byte for byte.

use anyhow::{bail, Result};
use std::path::Path;

/// `npm:left-pad@^1.3.0` -> the pieces.
#[derive(Debug, PartialEq)]
pub struct Spec {
    pub kind: String,
    pub name: String,
    pub range: Option<String>,
}

pub fn parse_spec(s: &str) -> Result<Spec> {
    let (kind, rest) = s
        .split_once(':')
        .ok_or_else(|| anyhow::anyhow!("write it as kind:name, e.g. npm:left-pad"))?;
    if !matches!(kind, "npm" | "mvn" | "git" | "pod") {
        bail!("no such dependency kind {kind:?} -- npm, mvn, git or pod");
    }
    // `@` SPLITS FROM THE RIGHT, because an npm scope starts with one:
    // `@scope/pkg@^1.0.0` is a name and a range, and splitting from the left
    // would make the name empty and the range the whole thing.
    let (name, range) = match rest.rfind('@') {
        Some(i) if i > 0 => (&rest[..i], Some(rest[i + 1..].to_string())),
        _ => (rest, None),
    };
    if name.is_empty() {
        bail!("{s:?} has no name");
    }
    Ok(Spec { kind: kind.into(), name: name.into(), range })
}

/// A git shorthand to a URL. `github.com/org/x` is what people type.
pub fn git_url(name: &str) -> String {
    if name.starts_with("http://") || name.starts_with("https://") || name.starts_with("git@") {
        name.to_string()
    } else {
        format!("https://{name}")
    }
}

/// Insert `entry` into the `:deps` map of `text`, or create the map.
///
/// Text editing, for the reason at the top of this file. The insertion point is
/// just after `:deps {`, so a new dependency lands first and the diff is one
/// line -- appending at the end would mean finding the matching brace, which is
/// a parser, which is the thing being avoided.
pub fn insert_dep(text: &str, entry: &str) -> String {
    match text.find(":deps") {
        Some(i) => match text[i..].find('{') {
            Some(off) => {
                let at = i + off + 1;
                let mut out = String::with_capacity(text.len() + entry.len() + 2);
                out.push_str(&text[..at]);
                out.push('\n');
                out.push_str(entry);
                out.push_str(&text[at..]);
                out
            }
            None => text.to_string(),
        },
        None => {
            // No `:deps` at all. A file that is just `{}` becomes one with a
            // deps map; anything else gets one appended before the final brace.
            let trimmed = text.trim_end();
            match trimmed.strip_suffix('}') {
                Some(head) => format!("{head}\n :deps {{\n{entry}}}}}\n"),
                None => format!("{{:deps {{\n{entry}}}}}\n"),
            }
        }
    }
}

/// Print what a resolved entry would look like in `deps.edn`.
pub fn entry_for(kind: &str, name: &str, version: &str, extra: &[(&str, &str)]) -> String {
    let mut s = format!("  {name} {{");
    match kind {
        "npm" => s.push_str(&format!(":npm/version {version:?}")),
        "mvn" => s.push_str(&format!(":mvn/version {version:?}")),
        "git" => s.push_str(&format!(":git/version {version:?}")),
        _ => s.push_str(&format!(":version {version:?}")),
    }
    for (k, v) in extra {
        s.push_str(&format!(" {k} {v:?}"));
    }
    s.push_str("}\n");
    s
}

pub fn deps_path(dir: &Path) -> std::path::PathBuf {
    dir.join("deps.edn")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_scoped_npm_name_keeps_its_at() {
        // Splitting from the LEFT would give an empty name and a range of
        // `scope/pkg@^1.0.0`, which is the bug this rfind exists to avoid.
        let s = parse_spec("npm:@scope/pkg@^1.0.0").unwrap();
        assert_eq!(s.name, "@scope/pkg");
        assert_eq!(s.range.as_deref(), Some("^1.0.0"));
    }

    #[test]
    fn a_scoped_name_with_no_range_is_all_name() {
        let s = parse_spec("npm:@scope/pkg").unwrap();
        assert_eq!(s.name, "@scope/pkg");
        assert_eq!(s.range, None);
    }

    #[test]
    fn an_unknown_kind_is_refused_by_name() {
        assert!(parse_spec("cargo:serde").is_err());
        assert!(parse_spec("left-pad").is_err());
    }

    #[test]
    fn a_git_shorthand_becomes_https_and_a_url_is_left_alone() {
        assert_eq!(git_url("github.com/org/x"), "https://github.com/org/x");
        assert_eq!(git_url("https://x.dev/y"), "https://x.dev/y");
        assert_eq!(git_url("git@github.com:org/x"), "git@github.com:org/x");
    }

    #[test]
    fn inserting_keeps_everything_else_byte_for_byte() {
        let before = ";; my project\n{:paths [\"src\"]\n :deps {org/a {:git/sha \"x\"}}}\n";
        let after = insert_dep(before, "  new/dep {:npm/version \"1.0.0\"}\n");
        assert!(after.starts_with(";; my project\n"), "the comment survived");
        assert!(after.contains("org/a {:git/sha \"x\"}"), "the old entry survived");
        assert!(after.contains("new/dep {:npm/version \"1.0.0\"}"), "the new one is there");
    }

    #[test]
    fn a_file_with_no_deps_map_gets_one() {
        let after = insert_dep("{:paths [\"src\"]}\n", "  a/b {:npm/version \"1\"}\n");
        assert!(after.contains(":deps {"), "{after}");
        assert!(after.contains(":paths"), "the rest survived: {after}");
    }
}

// ------------------------------------------------------------------ the command

/// A tiny flint program that resolves one coordinate and prints the answer.
///
/// Generated rather than written, and RUN rather than reimplemented: the
/// version `add` writes has to be the version a build picks, and the only way
/// to guarantee that is to ask the same code. A second resolver here would be
/// the `0035` mistake with a new subject.
fn resolve_program(kind: &str, name: &str, range: &str) -> String {
    let coord = match kind {
        "npm" => format!("{{:npm/version {range:?}}}"),
        "git" => format!("{{:git/url {:?} :git/version {range:?}}}", git_url(name)),
        "mvn" => format!("{{:mvn/version {range:?}}}"),
        _ => "{}".to_string(),
    };
    format!(
        r#"(ns depsadd (:require [flint.deps.resolve :as r]))
(defn main [_]
  (let [n (r/resolve-one '{name} {coord})]
    (if (nil? n)
      "!unresolved"
      (pr-str (select-keys n [:version :integrity :sha :tag :url])))))
"#
    )
}

/// `flint deps add kind:name[@range]`.
pub fn add(
    dir: &Path,
    spec: &str,
    run: impl Fn(&str, &str, &[String]) -> Result<String>,
) -> Result<()> {
    let s = parse_spec(spec)?;
    if s.kind == "pod" {
        bail!("pods are not resolvable yet (`doc/decisions/0037` step 9)");
    }
    let range = s.range.clone().unwrap_or_else(|| "*".to_string());
    let src = resolve_program(&s.kind, &s.name, &range);
    // THE URL THE USER NAMED is the authorisation. `:deps` defaults to the
    // public registries, so npm and Maven need nothing more; a git URL is not
    // in that list and must not be -- but somebody typing
    // `flint deps add git:github.com/org/x` has said which host they mean, on
    // the command line, which is exactly the invoker being the top of the
    // narrowing chain (`doc/decisions/0037`). Nothing wider is granted: the
    // allowlist entry is that URL and its children.
    let mut caps = vec!["deps".to_string()];
    if s.kind == "git" {
        caps.push(format!("deps:{}**", git_url(&s.name)));
    }
    let answer = run(&src, "depsadd/main", &caps)?;
    let answer = answer.trim();
    if answer.is_empty() || answer.contains("!unresolved") {
        bail!("could not resolve {spec}");
    }
    // The answer is EDN written by the plan. Read the two fields that matter
    // with a scan rather than an EDN parser: this is the host's half, the
    // shape is one flat map that flint just printed, and a parser here would be
    // a second reader of a format the other side already owns.
    let field = |k: &str| -> Option<String> {
        let at = answer.find(&format!("{k} "))?;
        let rest = &answer[at + k.len() + 1..];
        let rest = rest.trim_start();
        let rest = rest.strip_prefix('"')?;
        let end = rest.find('"')?;
        Some(rest[..end].to_string())
    };
    let version = field(":version").unwrap_or_default();
    let mut extra: Vec<(&str, String)> = Vec::new();
    if let Some(i) = field(":integrity") {
        if !i.is_empty() {
            extra.push((":npm/integrity", i));
        }
    }
    if let Some(sha) = field(":sha") {
        if !sha.is_empty() {
            extra.push((":git/sha", sha));
        }
    }
    if s.kind == "git" {
        extra.push((":git/url", git_url(&s.name)));
    }
    if version.is_empty() {
        bail!("{spec} resolved to nothing with a version -- {answer}");
    }
    let borrowed: Vec<(&str, &str)> =
        extra.iter().map(|(k, v)| (*k, v.as_str())).collect();
    // The NAME as written in `deps.edn`. A git shorthand becomes the last path
    // segment, because `github.com/org/lib` is a URL and not a symbol.
    let dep_name = if s.kind == "git" {
        s.name.rsplit('/').next().unwrap_or(&s.name).to_string()
    } else {
        s.name.clone()
    };
    let entry = entry_for(&s.kind, &dep_name, &version, &borrowed);
    let path = deps_path(dir);
    let before = std::fs::read_to_string(&path).unwrap_or_else(|_| "{}\n".to_string());
    let after = insert_dep(&before, &entry);
    std::fs::write(&path, after)?;
    // PINNED, and it says so: the point of `add` writing an exact version is
    // that a reader can see it happened.
    print!("added {dep_name} {version}");
    for (k, v) in &extra {
        if *k != ":git/url" {
            print!("  {k} {}", &v[..v.len().min(16)]);
        }
    }
    println!();
    Ok(())
}
