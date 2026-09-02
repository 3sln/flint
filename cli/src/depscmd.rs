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
        // CANONICAL: a tag and a sha, which is what `deps.edn` has always had.
        "git" => s.push_str(&format!(":git/tag {version:?}")),
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
        "git" => String::new(),
        "mvn" => format!("{{:mvn/version {range:?}}}"),
        _ => "{}".to_string(),
    };
    if kind == "git" {
        // GIT CHOOSES A TAG ONCE, at add time. `:git/version` used to record a
        // range and re-resolve it on every build, which made a checkout mean
        // different things on different days -- the same objection this project
        // raises to a branch. What is written down is a tag and its sha.
        return format!(
            r#"(ns depsadd (:require [flint.deps.resolve :as r]))
(defn main [_]
  (let [n (r/newest-tag {url:?})]
    (if (nil? n) "!unresolved"
      (pr-str {{:version (:version n) :tag (:tag n) :sha (:sha n)}}))))
"#,
            url = git_url(name)
        );
    }
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

/// A program that reads `deps.edn`, plans it, and prints one of the views.
///
/// The VIEWS ARE IN `flint.deps.resolve` too. The CLI would otherwise have to
/// re-derive the graph to render it, and a second derivation is a second
/// answer -- the same reason `add` runs the plan rather than reimplementing it.
fn view_program(view: &str, target: &str) -> String {
    let body = match view {
        "tree" => "(str/join \"\\n\" (r/tree-lines p))".to_string(),
        "why" => format!("(str/join \"\\n\" (r/why-lines p '{target}))"),
        // `pin` prints the overrides map. The CLI writes it; deciding what goes
        // in it is the plan's job.
        _ => "(pr-str (r/pins p))".to_string(),
    };
    format!(
        r#"(ns depsview
  (:require [flint.deps.resolve :as r] [clojure.string :as str]
            [clojure.edn :as edn] [flint.sys.fs :as fs]))
(defn main [_]
  (let [d (edn/read-string (fs/read-file "deps.edn"))
        p (r/plan (or (:deps d) {{}}) (or (:flint/overrides d) {{}}))]
    (if (seq (:refused p))
      (str "!refused " (pr-str (:refused p)))
      {body})))
"#
    )
}

/// The bump program: what would change, as EDN, for the CLI to render.
fn bump_program(level: &str) -> String {
    let lvl = if level.is_empty() { "nil".to_string() } else { level.to_string() };
    format!(
        r#"(ns depsbump
  (:require [flint.deps.resolve :as r] [clojure.edn :as edn] [flint.sys.fs :as fs]))
(defn main [_]
  (let [d (edn/read-string (fs/read-file "deps.edn"))]
    (pr-str (r/bump-plan (or (:deps d) {{}}) {lvl}))))
"#
    )
}

/// `flint deps bump [:patch|:minor|:major]`.
pub fn bump(
    level: &str,
    run: impl Fn(&str, &str, &[String]) -> Result<String>,
) -> Result<String> {
    run(&bump_program(level), "depsbump/main", &["deps".to_string(), "fs".to_string()])
}

/// Rewrite one dependency's version in place, by text.
pub fn set_version(text: &str, dep: &str, to: &str) -> String {
    // Find the entry, then the FIRST version string after it. Narrow on
    // purpose: a wider search would rewrite the next dependency's version when
    // this one has no version key at all.
    let Some(at) = text.find(dep) else { return text.to_string() };
    let rest = &text[at..];
    let Some(close) = rest.find('}') else { return text.to_string() };
    let entry = &rest[..close];
    for key in [":npm/version", ":git/version", ":mvn/version"] {
        if let Some(k) = entry.find(key) {
            let after = &entry[k + key.len()..];
            if let Some(q1) = after.find('"') {
                if let Some(q2) = after[q1 + 1..].find('"') {
                    let s = at + k + key.len() + q1 + 1;
                    let e = s + q2;
                    return format!("{}{}{}", &text[..s], to, &text[e..]);
                }
            }
        }
    }
    text.to_string()
}

/// The agree program: which repositories are named with different tags, and
/// what they could all take instead.
fn agree_program(apply: bool) -> String {
    let tail = if apply {
        // The OVERRIDES that settle it, ready to write. A pin and an agreement
        // are the same operation (`doc/decisions/0037`), so they produce the
        // same thing.
        r#"(pr-str (reduce (fn [m row]
                             (if (:to row)
                               (reduce (fn [mm d] (assoc mm d {:git/url (:url row)
                                                               :git/tag (:to row)
                                                               :git/sha (:sha row)}))
                                       m (apply concat (vals (:from row))))
                               m))
                           {} rows))"#
    } else {
        r#"(str/join "
"
             (mapv (fn [row]
                     (str (:url row) "
"
                          (str/join "
"
                            (mapv (fn [e] (str "  " (key e) "  <- " (str/join ", " (val e))))
                                  (:from row)))
                          "
  => " (or (:to row) "no version in these tags; choose by hand")))
                   rows))"#
    };
    format!(
        r#"(ns depsagree
  (:require [flint.deps.resolve :as r] [clojure.string :as str]
            [clojure.edn :as edn] [flint.sys.fs :as fs]))
(defn main [_]
  (let [d (edn/read-string (fs/read-file "deps.edn"))
        deps (merge (or (:deps d) {{}}) (or (:flint/overrides d) {{}}))
        rows (r/tag-agreement deps)]
    (if (empty? rows) "" {tail})))
"#
    )
}

/// `flint deps agree [--apply]`.
pub fn agree(
    apply: bool,
    run: impl Fn(&str, &str, &[String]) -> Result<String>,
) -> Result<String> {
    run(&agree_program(apply), "depsagree/main",
        &["deps".to_string(), "fs".to_string(),
          // Every git host the file names -- resolving the agreed tag's sha
          // needs to reach the repository, and nothing wider than that.
          "deps:https://**".to_string()])
}

/// `flint deps tree|why|pin`.
pub fn view(
    spec: &str,
    target: &str,
    run: impl Fn(&str, &str, &[String]) -> Result<String>,
) -> Result<String> {
    let src = view_program(spec, target);
    // `:fs` to read `deps.edn`, `:deps` to resolve what is in it. Both, and
    // nothing else -- a view does not write.
    let out = run(&src, "depsview/main", &["deps".to_string(), "fs".to_string()])?;
    if out.trim_start().starts_with("!refused") {
        bail!("{}", out.trim_start().trim_start_matches("!refused").trim());
    }
    Ok(out)
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
    let mut version = field(":version").unwrap_or_default();
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
    // For git the entry records the TAG, not the version it parsed to.
    if s.kind == "git" {
        if let Some(t) = field(":tag") {
            if !t.is_empty() {
                version = t;
            }
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

/// Replace or insert `:flint/overrides` in `text`.
///
/// Text editing, like `insert_dep` and for the same reason: a `deps.edn` is a
/// file somebody wrote. Replacing an existing block means finding its extent,
/// which is a brace scan rather than a parser -- and a brace scan is honest
/// here because what it is scanning was written by `pins`, which never emits a
/// string containing a brace.
pub fn set_overrides(text: &str, edn: &str) -> String {
    let block = format!(" :flint/overrides {edn}");
    match text.find(":flint/overrides") {
        None => {
            let trimmed = text.trim_end();
            match trimmed.strip_suffix('}') {
                Some(head) => format!("{head}\n{block}}}\n"),
                None => format!("{{{block}}}\n"),
            }
        }
        Some(i) => {
            // From the key to the end of its map value.
            let rest = &text[i..];
            let Some(open) = rest.find('{') else { return text.to_string() };
            let mut depth = 0i32;
            let mut end = None;
            for (off, c) in rest[open..].char_indices() {
                match c {
                    '{' => depth += 1,
                    '}' => {
                        depth -= 1;
                        if depth == 0 {
                            end = Some(open + off + 1);
                            break;
                        }
                    }
                    _ => {}
                }
            }
            match end {
                Some(e) => format!("{}{}{}", &text[..i], block.trim_start(), &text[i + e..]),
                None => text.to_string(),
            }
        }
    }
}

/// One line of a bump plan.
pub struct Bump {
    pub dep: String,
    pub from: String,
    pub to: String,
    pub major: bool,
}

/// The EDN `bump-plan` printed, as rows.
///
/// A scan, like the rest of this file's reading: the shape is a flat vector of
/// flat maps that flint just printed, and a parser here would be a second
/// reader of a format the other side owns.
pub fn parse_bumps(edn: &str) -> Vec<Bump> {
    let mut out = Vec::new();
    for chunk in edn.split(":dep ").skip(1) {
        let field = |k: &str| -> String {
            match chunk.find(k) {
                None => String::new(),
                Some(i) => {
                    let after = chunk[i + k.len()..].trim_start();
                    match after.strip_prefix('"') {
                        Some(r) => r.find('"').map(|e| r[..e].to_string()).unwrap_or_default(),
                        None => String::new(),
                    }
                }
            }
        };
        let dep = chunk
            .split_whitespace()
            .next()
            .unwrap_or("")
            .trim_matches(|c| c == ',' || c == '}')
            .to_string();
        if dep.is_empty() {
            continue;
        }
        out.push(Bump {
            dep,
            from: field(":from"),
            to: field(":to"),
            major: chunk.contains(":crosses-major? true"),
        });
    }
    out
}

#[cfg(test)]
mod bump_tests {
    use super::*;

    #[test]
    fn a_bump_row_reads_back() {
        let edn = r#"[{:dep left-pad, :from "1.2.0", :to "1.3.0", :crosses-major? false}]"#;
        let rows = parse_bumps(edn);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].dep, "left-pad");
        assert_eq!(rows[0].to, "1.3.0");
        assert!(!rows[0].major);
    }

    #[test]
    fn crossing_a_major_is_flagged() {
        let edn = r#"[{:dep a, :from "1.0.0", :to "2.0.0", :crosses-major? true}]"#;
        assert!(parse_bumps(edn)[0].major);
    }

    #[test]
    fn set_version_touches_only_the_named_dependency() {
        let before = "{:deps {a {:npm/version \"1.0.0\"} b {:npm/version \"1.0.0\"}}}";
        let after = set_version(before, "b", "2.0.0");
        assert!(after.contains("a {:npm/version \"1.0.0\"}"), "{after}");
        assert!(after.contains("b {:npm/version \"2.0.0\"}"), "{after}");
    }
}
