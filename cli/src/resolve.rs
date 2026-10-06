//! One namespace's answer, for the host-driven compile path.
//!
//! `flint.project/files-resolver` and `flint.project/file-answer` answer a
//! namespace resolution request against a flat `files` map and a
//! `workspaces` vector -- the same two things `build_spec_impl` builds and
//! renders as the `:workspaces` EDN today (`DECISIONS.md#namespaces-over-the-system-port`).
//! This is that same question asked from Rust, one namespace at a time, over
//! the resolver protocol the decision describes rather than the EDN spec
//! envelope. It reads `crate::spec_inputs` rather than rebuilding the files
//! and workspaces list -- two lists that enumerate the same thing drift, and
//! AGENTS.md says to make one read the other instead.
//!
//! **This resolver reads only symbol and keyword fragments.** A workspace's
//! `:flint/tag-readers`, `:flint/prelude`, `:flint/capabilities-grant` and
//! `:flint/capabilities-guard` are read here with a small tokenizer rather
//! than a real EDN reader, because the only thing anything in this tree has
//! ever written into them is symbols and keywords. This is the step-2 TEST
//! resolver; §8 step 3 of `DECISIONS.md#namespaces-over-the-system-port`
//! builds the CLI's real one, on step 1's kin reader. Until then a fragment this cannot parse comes
//! back as `{:error {:message ..}}` for that one namespace rather than being
//! guessed at.

use crate::{Body, SpecInputs, WsEntry};
use flint_rt::codec::Val;
use std::path::PathBuf;

/// The files and workspaces one compile needs, answered a namespace at a
/// time. A thin wrapper over `SpecInputs` -- everything it holds was already
/// computed by `crate::spec_inputs`, in the order `files-resolver` and
/// `file-answer` read it in.
pub(crate) struct Answers {
    inputs: SpecInputs,
}

impl Answers {
    /// The same inputs `build_spec_split` would hand the compiler for `srcs`
    /// (split = true): the standard library as pre-read forms unless
    /// `FLINT_PREREAD=0` asks for text, which is the same rule
    /// `build_spec_impl` applies and the same reason -- a suspected pre-read
    /// defect is ruled in or out by reading the library as text instead
    /// (`DECISIONS.md#stdlib-preread`).
    pub(crate) fn new(srcs: &[PathBuf], pods: &[(String, Vec<String>)],
                      features: Option<&[String]>) -> anyhow::Result<Answers> {
        // `:flint/nested` decides whether `flint.ception` is offered at all --
        // the same computation `build_spec_impl` makes, read here rather than
        // exported separately so the two can't disagree about what "nested"
        // means.
        let nested = features.map_or(true, |f| f.iter().any(|x| x == ":flint/nested"));
        let as_text = std::env::var("FLINT_PREREAD").is_ok_and(|v| v == "0");
        let inputs = crate::spec_inputs(srcs, pods, nested, as_text)?;
        Ok(Answers { inputs })
    }

    /// What `files-resolver` composed with `file-answer` would answer for
    /// `ns`, as a wire value -- `Val::Nil` when there is no source and no
    /// virtual entry claims it.
    pub(crate) fn answer(&self, ns: &str) -> Val {
        // `flint.project/ns->path`'s mapping, read off `crate::ns_to_path`
        // rather than reimplemented: that function already applies it (with a
        // `.cljc` suffix this resolver has no use for, so it is trimmed
        // straight back off).
        let full = crate::ns_to_path(ns);
        let base = full.strip_suffix(".cljc").unwrap_or(&full);
        let base_slash = format!("{base}/");

        // VIRTUAL FIRST -- `files-resolver` checks every virtual workspace
        // before ever looking at `files`, because a virtual namespace has no
        // file to find and looking for one would report it missing.
        for w in &self.inputs.workspaces {
            let WsEntry::Virtual { prefix, name, vars } = w else { continue };
            if !(prefix.is_empty() || base.starts_with(prefix.as_str())
                 || base_slash.starts_with(prefix.as_str())) {
                continue;
            }
            let vars_val = vars.iter().map(|(vname, arities)| {
                let mut entries = vec![(kw("name"), name_sym(vname))];
                if let Some(a) = arities {
                    entries.push((kw("arities"),
                                  Val::Vector(a.iter().map(|x| Val::Int(*x as i64)).collect())));
                }
                Val::Map(entries)
            }).collect();
            let mut m = vec![
                (kw("virtual"), Val::Bool(true)),
                (kw("vars"), Val::Vector(vars_val)),
                (kw("file"), Val::Str(ns.to_string())),
            ];
            if let Some(nv) = name_value(name) {
                m.push((kw("workspace"), nv));
            }
            // No `:grants`/`:guard` here: a pod or `crate::sys::catalogue`
            // entry never carries them (`spec_inputs` never sets them for a
            // `WsEntry::Virtual`), and the guest treats an absent key as the
            // empty set it would have computed from nothing anyway.
            return Val::Map(m);
        }

        // `source-extensions` order: `.fln` before `.cljc` before `.clj`,
        // because that is the order a namespace's source wins under when more
        // than one exists (`flint.project/source-extensions`).
        let path = [".fln", ".cljc", ".clj"].iter()
            .map(|ext| format!("{base}{ext}"))
            .find(|cand| self.inputs.files.contains_key(cand));
        let Some(path) = path else { return Val::Nil };

        // `file-answer`'s workspace search is NOT limited to virtual entries
        // -- the first prefix match of any kind wins, which is how a project
        // file picks up its `deps.edn` workspace.
        let matched = self.inputs.workspaces.iter().find(|w| {
            let prefix = match w {
                WsEntry::Virtual { prefix, .. } | WsEntry::Source { prefix, .. } => prefix,
            };
            prefix.is_empty() || path.starts_with(prefix.as_str())
        });

        let mut m = vec![(kw("file"), Val::Str(path.clone()))];
        match &self.inputs.files[&path] {
            Body::Text(t) => m.push((kw("source"), Val::Str(t.clone()))),
            Body::Forms(b) => m.push((kw("forms"), Val::Bytes(b.to_vec()))),
        }

        match matched {
            None => {}
            // A VIRTUAL entry matching a FILE path is an edge case that
            // should not arise in practice (a pod or `crate::sys::catalogue`
            // prefix and a project's namespace-derived path are different
            // address spaces), but `file-answer` does not special-case it
            // either -- it just reads `:name` off whatever `w` is. This takes
            // the name and stops there: there is no `:tags`/`:prelude`/
            // `:grants`/`:guard` fragment on a virtual entry to read.
            Some(WsEntry::Virtual { name, .. }) => {
                if let Some(nv) = name_value(name) {
                    m.push((kw("workspace"), nv));
                }
            }
            Some(WsEntry::Source { name, ws, prefix }) => {
                if let Some(nv) = name_value(name) {
                    m.push((kw("workspace"), nv));
                }
                let label = if !name.is_empty() { name.as_str() } else { prefix.as_str() };
                let tags = match parse_tag_pairs(&ws.tags, label) {
                    Ok(v) => v,
                    Err(e) => return error_val(e),
                };
                let prelude = match parse_symbols(&ws.prelude, label, "prelude") {
                    Ok(v) => v,
                    Err(e) => return error_val(e),
                };
                let grants = match parse_keywords(&ws.grants, label, "grants") {
                    Ok(v) => v,
                    Err(e) => return error_val(e),
                };
                let guard = match parse_keywords(&ws.guard, label, "guard") {
                    Ok(v) => v,
                    Err(e) => return error_val(e),
                };
                m.push((kw("tags"), Val::Map(tags)));
                m.push((kw("prelude"), Val::Vector(prelude)));
                m.push((kw("grants"), Val::Vector(grants)));
                m.push((kw("guard"), Val::Vector(guard)));
            }
        }
        Val::Map(m)
    }
}

fn kw(name: &str) -> Val {
    Val::Keyword(None, name.to_string())
}

/// `ns/name` -> a namespaced symbol, a bare name -> an unnamespaced one.
/// Shared by var names, workspace names and prelude/tag entries, because
/// `flint.project` reads all of them the same way: a symbol is a symbol.
fn name_sym(text: &str) -> Val {
    match text.split_once('/') {
        Some((ns, name)) if !ns.is_empty() && !name.is_empty() =>
            Val::Symbol(Some(ns.to_string()), name.to_string()),
        _ => Val::Symbol(None, text.to_string()),
    }
}

/// The `:name` text `workspace_entry` spliced into the EDN, read back as a
/// wire value -- a bare symbol most of the time (`flint/flint`, `flint/sys`,
/// `pod/pod`), or the quoted directory-path fallback `workspace_entry` writes
/// for a project with no `:flint/workspace` of its own. `None` when there is
/// nothing to say, which is how the caller knows to omit `:workspace` rather
/// than send an empty one.
fn name_value(text: &str) -> Option<Val> {
    if text.is_empty() {
        return None;
    }
    if let Some(inner) = text.strip_prefix('"').and_then(|s| s.strip_suffix('"')) {
        let mut s = String::with_capacity(inner.len());
        let mut chars = inner.chars();
        while let Some(c) = chars.next() {
            if c == '\\' {
                match chars.next() {
                    Some('"') => s.push('"'),
                    Some('\\') => s.push('\\'),
                    Some(other) => { s.push('\\'); s.push(other); }
                    None => s.push('\\'),
                }
            } else {
                s.push(c);
            }
        }
        return Some(Val::Str(s));
    }
    Some(name_sym(text))
}

fn error_val(message: String) -> Val {
    Val::Map(vec![(kw("error"), Val::Map(vec![(kw("message"), Val::Str(message))]))])
}

/// Whitespace or comma separated tokens -- EDN treats a comma as whitespace,
/// and `edn_block` leaves either behind untouched.
fn split_tokens(frag: &str) -> Vec<&str> {
    frag.split(|c: char| c.is_whitespace() || c == ',')
        .filter(|s| !s.is_empty())
        .collect()
}

/// A token this tokenizer refuses outright: a brace, bracket or quote means a
/// map, vector or string sits where a bare symbol or keyword was expected, and
/// a number is neither. Guessing at any of these is the bug this resolver
/// exists to not have -- see the module doc.
fn has_structure(tok: &str) -> bool {
    tok.contains(['{', '}', '[', ']', '"']) || tok.parse::<f64>().is_ok()
}

fn unsupported(workspace: &str, field: &str, tok: &str) -> String {
    format!(
        "workspace {workspace}'s :{field} contains {tok:?}, which is not a plain symbol or \
         keyword -- this resolver reads only symbol and keyword fragments \
         (DECISIONS.md#namespaces-over-the-system-port, step 3 builds the full one)")
}

/// `:flint/capabilities-grant`/`:flint/capabilities-guard` fragments:
/// whitespace- or comma-separated keywords, optionally namespaced
/// (`:ns/kw`).
fn parse_keywords(frag: &str, workspace: &str, field: &str) -> Result<Vec<Val>, String> {
    let mut out = Vec::new();
    for tok in split_tokens(frag) {
        if has_structure(tok) {
            return Err(unsupported(workspace, field, tok));
        }
        let Some(rest) = tok.strip_prefix(':').filter(|r| !r.is_empty()) else {
            return Err(unsupported(workspace, field, tok));
        };
        out.push(match rest.split_once('/') {
            Some((ns, name)) if !ns.is_empty() && !name.is_empty() =>
                Val::Keyword(Some(ns.to_string()), name.to_string()),
            _ => Val::Keyword(None, rest.to_string()),
        });
    }
    Ok(out)
}

/// `:flint/prelude` fragments: whitespace-separated symbols.
fn parse_symbols(frag: &str, workspace: &str, field: &str) -> Result<Vec<Val>, String> {
    let mut out = Vec::new();
    for tok in split_tokens(frag) {
        if has_structure(tok) || tok.starts_with(':') {
            return Err(unsupported(workspace, field, tok));
        }
        out.push(name_sym(tok));
    }
    Ok(out)
}

/// `:flint/tag-readers` fragments: alternating symbol pairs, tag then var.
fn parse_tag_pairs(frag: &str, workspace: &str) -> Result<Vec<(Val, Val)>, String> {
    let toks = split_tokens(frag);
    if toks.len() % 2 != 0 {
        return Err(format!(
            "workspace {workspace}'s :tags has {} entries, which cannot be paired up two at a \
             time -- this resolver reads only symbol and keyword fragments \
             (DECISIONS.md#namespaces-over-the-system-port, step 3 builds the full one)",
            toks.len()));
    }
    let mut out = Vec::new();
    for pair in toks.chunks(2) {
        for tok in pair {
            if has_structure(tok) || tok.starts_with(':') {
                return Err(unsupported(workspace, "tags", tok));
            }
        }
        out.push((name_sym(pair[0]), name_sym(pair[1])));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn keywords_tokenize() {
        let v = parse_keywords(":host :vars", "ws", "grants").unwrap();
        assert_eq!(v, vec![Val::Keyword(None, "host".into()), Val::Keyword(None, "vars".into())]);
    }

    #[test]
    fn tag_pairs_tokenize() {
        let v = parse_tag_pairs("t1 a/f t2 b/g", "ws").unwrap();
        assert_eq!(v, vec![
            (Val::Symbol(None, "t1".into()), Val::Symbol(Some("a".into()), "f".into())),
            (Val::Symbol(None, "t2".into()), Val::Symbol(Some("b".into()), "g".into())),
        ]);
    }

    #[test]
    fn prelude_symbols_tokenize() {
        let v = parse_symbols("clojure.core x", "ws", "prelude").unwrap();
        assert_eq!(v, vec![Val::Symbol(None, "clojure.core".into()), Val::Symbol(None, "x".into())]);
    }

    #[test]
    fn unsupported_fragment_is_refused() {
        assert!(parse_keywords("{:ns a}", "ws", "grants").is_err());
        assert!(parse_tag_pairs("{:ns a}", "ws").is_err());
    }

    /// A tiny project: `app.cljc` with no workspace of its own (no
    /// `deps.edn`), and a namespace that does not exist at all. One directory
    /// PER TEST (`tag`): the tests run in parallel, and a shared one was
    /// deleted under a sibling mid-read.
    fn temp_project(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("flint-resolve-test-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("nope")).unwrap();
        fs::write(dir.join("app.cljc"), "(ns app)\n").unwrap();
        fs::write(dir.join("nope").join("x.cljc"), "(ns nope.x)\n").unwrap();
        dir
    }

    #[test]
    fn answers_a_project_source_namespace_with_no_workspace() {
        let dir = temp_project("t1");
        let a = Answers::new(&[dir.clone()], &[], None).unwrap();
        let v = a.answer("app");
        assert_eq!(v.get("file").and_then(Val::as_str), Some("app.cljc"));
        assert!(v.get("source").is_some());
        assert!(v.get("workspace").is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn answers_a_stdlib_namespace_as_forms_with_its_workspace() {
        let dir = temp_project("t2");
        let a = Answers::new(&[dir.clone()], &[], None).unwrap();
        let v = a.answer("clojure.core");
        assert_eq!(v.get("file").and_then(Val::as_str), Some("clojure/core.cljc"));
        assert!(v.get("forms").is_some());
        assert_eq!(v.get("workspace"), Some(&Val::Symbol(Some("flint".into()), "flint".into())));
        // WHAT `lib/deps.edn` GRANTS, read from it rather than restated: this
        // said `[:host :vars]` and went stale the day the control plane moved
        // into the runtime and the library stopped holding `:vars`.
        let declared = crate::edn_block(crate::STDLIB_DEPS, ":flint/capabilities-grant", '[', ']');
        let want = parse_keywords(&declared, "flint/flint", "grants").unwrap();
        assert!(!want.is_empty());
        assert_eq!(v.get("grants"), Some(&Val::Vector(want)));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn answers_a_virtual_namespace_with_its_vars() {
        let dir = temp_project("t3");
        let a = Answers::new(&[dir.clone()], &[], None).unwrap();
        let v = a.answer("flint.sys.fs");
        assert_eq!(v.get("virtual"), Some(&Val::Bool(true)));
        match v.get("vars") {
            Some(Val::Vector(vs)) => assert!(!vs.is_empty()),
            other => panic!("expected a non-empty :vars vector, got {other:?}"),
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn answers_nil_for_an_unknown_namespace() {
        let dir = temp_project("t4");
        let a = Answers::new(&[dir.clone()], &[], None).unwrap();
        assert_eq!(a.answer("nowhere.at.all"), Val::Nil);
        let _ = fs::remove_dir_all(&dir);
    }
}
