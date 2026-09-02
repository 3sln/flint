//! `flint.deps.*`: dependency resolution, served over ports
//! (`doc/decisions/0037`).
//!
//! Virtual namespaces like `flint.sys.*`, and served by the same pump. What
//! makes them a separate file is the rule that governs all of them:
//!
//! > **Rust matches, flint decides.**
//!
//! Version arithmetic ends up in two languages -- `semver` here, resolving at
//! FETCH time, and `flint.deps` in `.cljc`, comparing at PLAN time where the
//! graph lives. Two implementations of "which version wins" is exactly the
//! shape `0035` records going wrong, so there is only one: **`resolve` returns
//! EVERY matching version and never picks.** The plan is authoritative.
//!
//! One capability, `:deps`, for npm, Maven and git together. A build that may
//! fetch from one may fetch from the others: they are one authority -- reach
//! out and bring code in -- and splitting them would be three grants always
//! given together.

use crate::policy::Policy;
use crate::sys::{Answer, Service};
use flint_rt::codec::{Val, Wire};
use std::path::{Path, PathBuf};

fn str_arg<'a>(args: &'a [Val], i: usize, what: &str) -> Result<&'a str, String> {
    args.get(i)
        .and_then(|v| v.as_str())
        .ok_or_else(|| format!("argument {i} ({what}) has to be a string"))
}

/// Fetch a URL, subject to the `:deps` policy.
///
/// `:deps` carries its own allowlist for the same reason `:slurp` does, and it
/// DEFAULTS to the public registries rather than to nothing -- because a
/// dependency resolver that reaches nowhere by default is a resolver nobody can
/// use, and the registries are the whole point of holding `:deps` at all. A
/// project that wants an internal mirror and nothing else says so.
fn get(url: &str, p: &Policy) -> Result<Vec<u8>, String> {
    if !p.deps_allows(url) {
        return Err(format!(
            "{url} is not in this program's :deps allowlist"
        ));
    }
    let resp = ureq::get(url).call().map_err(|e| format!("{url}: {e}"))?;
    let mut body = Vec::new();
    use std::io::Read as _;
    // 512 MB: a jar or a tarball, not a stream. Bigger than `slurp`'s limit
    // because an artifact legitimately is bigger than a config file, and still
    // bounded because a registry is not trusted to be sane.
    let mut r = resp.into_body().into_reader().take(512 * 1024 * 1024);
    r.read_to_end(&mut body).map_err(|e| format!("{url}: {e}"))?;
    Ok(body)
}

fn sha256_hex(b: &[u8]) -> String {
    use sha2::Digest as _;
    let mut h = sha2::Sha256::new();
    h.update(b);
    hex::encode(h.finalize())
}

/// Where a fetched dependency lands. Under the project, so a checkout is
/// self-contained and a stale one is deletable by hand.
fn cache_dir(kind: &str, name: &str, version: &str) -> PathBuf {
    // The NAME is sanitised, not trusted: `@scope/pkg` and `org.clojure/x` both
    // contain separators, and a registry that answered `../..` would otherwise
    // choose where this writes.
    let safe = |s: &str| {
        s.chars()
            .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' { c } else { '_' })
            .collect::<String>()
    };
    PathBuf::from(".flint")
        .join("deps")
        .join(kind)
        .join(safe(name))
        .join(safe(version))
}

// ------------------------------------------------------------- flint.deps.npm

pub struct Npm {
    pub registry: String,
}

impl Default for Npm {
    fn default() -> Self {
        Npm { registry: "https://registry.npmjs.org".into() }
    }
}

impl Npm {
    pub fn name_static(&self) -> &'static str {
        "flint.deps.npm"
    }
    fn packument(&self, name: &str, p: &Policy) -> Result<serde_json::Value, String> {
        let url = format!("{}/{}", self.registry, name);
        let b = get(&url, p)?;
        serde_json::from_slice(&b).map_err(|e| format!("{url}: not JSON: {e}"))
    }
}

impl Service for Npm {
    fn name(&self) -> &str {
        "flint.deps.npm"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![
            ("versions", &[1]),
            ("resolve", &[2]),
            ("manifest", &[2]),
            ("fetch", &[2]),
        ]
    }
    fn invoke(&mut self, var: &str, args: &[Val], p: &Policy) -> Answer {
        let mut w = Wire::new();
        match var {
            "versions" => {
                let name = str_arg(args, 0, "package")?;
                let doc = self.packument(name, p)?;
                let mut vs: Vec<String> = doc["versions"]
                    .as_object()
                    .map(|o| o.keys().cloned().collect())
                    .unwrap_or_default();
                vs.sort();
                w.vector(vs.len() as u32);
                for v in vs {
                    w.string(&v);
                }
            }
            // EVERY match, newest last, and the caller picks. This is the whole
            // of "Rust matches, flint decides": a `resolve` that returned one
            // version would be a second version-conflict policy living in
            // another language from the first.
            "resolve" => {
                let name = str_arg(args, 0, "package")?;
                let range = str_arg(args, 1, "range")?;
                let req = semver::VersionReq::parse(range)
                    .map_err(|e| format!("{range:?} is not a semver range: {e}"))?;
                let doc = self.packument(name, p)?;
                let empty = serde_json::Map::new();
                let vers = doc["versions"].as_object().unwrap_or(&empty);
                let mut hits: Vec<(semver::Version, String, String)> = Vec::new();
                for (v, meta) in vers {
                    // A version npm accepts that semver does not is SKIPPED
                    // rather than fatal: one malformed entry in a package's
                    // history must not make the package unresolvable.
                    let Ok(parsed) = semver::Version::parse(v) else { continue };
                    if req.matches(&parsed) {
                        let integrity = meta["dist"]["integrity"]
                            .as_str()
                            .or_else(|| meta["dist"]["shasum"].as_str())
                            .unwrap_or("")
                            .to_string();
                        hits.push((parsed, v.clone(), integrity));
                    }
                }
                hits.sort_by(|a, b| a.0.cmp(&b.0));
                w.vector(hits.len() as u32);
                for (_, v, integrity) in hits {
                    w.map(2);
                    w.keyword(None, "version");
                    w.string(&v);
                    w.keyword(None, "integrity");
                    w.string(&integrity);
                }
            }
            "manifest" => {
                let name = str_arg(args, 0, "package")?;
                let version = str_arg(args, 1, "version")?;
                let doc = self.packument(name, p)?;
                let m = &doc["versions"][version];
                if m.is_null() {
                    return Err(format!("{name} has no version {version}"));
                }
                w.map(3);
                w.keyword(None, "deps");
                let deps = m["dependencies"].as_object().cloned().unwrap_or_default();
                w.map(deps.len() as u32);
                for (k, v) in &deps {
                    w.string(k);
                    w.string(v.as_str().unwrap_or(""));
                }
                w.keyword(None, "tarball");
                w.string(m["dist"]["tarball"].as_str().unwrap_or(""));
                w.keyword(None, "integrity");
                w.string(m["dist"]["integrity"].as_str().unwrap_or(""));
            }
            "fetch" => {
                let name = str_arg(args, 0, "package")?;
                let version = str_arg(args, 1, "version")?;
                let dir = cache_dir("npm", name, version);
                // ALREADY THERE is not an error and not a re-fetch. The stamp
                // is written last, so a fetch that died halfway is not mistaken
                // for one that finished.
                let stamp = dir.join(".flint-fetched");
                if stamp.exists() {
                    w.string(&dir.display().to_string());
                    return Ok(w);
                }
                let doc = self.packument(name, p)?;
                let m = &doc["versions"][version];
                let url = m["dist"]["tarball"]
                    .as_str()
                    .ok_or_else(|| format!("{name}@{version} has no tarball"))?;
                let body = get(url, p)?;
                let digest = sha256_hex(&body);
                std::fs::create_dir_all(&dir).map_err(|e| format!("{dir:?}: {e}"))?;
                unpack_tgz(&body, &dir)?;
                std::fs::write(&stamp, format!("{url}\nsha256:{digest}\n"))
                    .map_err(|e| format!("{stamp:?}: {e}"))?;
                // An npm tarball unpacks into `package/`, which is the source
                // root -- a namespace path is relative to that and not to the
                // directory holding it.
                w.string(&dir.join("package").display().to_string());
            }
            other => return Err(format!("flint.deps.npm has no {other}")),
        }
        Ok(w)
    }
}

/// Unpack a gzipped tar into `dir`, refusing any entry that would escape it.
///
/// The check is the reason this is written out rather than handed to `unpack`:
/// a tar entry names its own path, and an archive is exactly the place an
/// attacker puts `../../.ssh/authorized_keys`. `tar`'s own `unpack` does defend
/// against this, and depending on that quietly means depending on a version of
/// it -- so the refusal is here, where it can be read.
fn unpack_tgz(body: &[u8], dir: &Path) -> Result<(), String> {
    let gz = flate2::read::GzDecoder::new(body);
    let mut ar = tar::Archive::new(gz);
    for e in ar.entries().map_err(|e| format!("tar: {e}"))? {
        let mut e = e.map_err(|e| format!("tar: {e}"))?;
        let path = e.path().map_err(|e| format!("tar: {e}"))?.into_owned();
        let out = crate::sys::under(dir, &path.to_string_lossy())
            .map_err(|m| format!("tar entry {path:?}: {m}"))?;
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("{parent:?}: {e}"))?;
        }
        e.unpack(&out).map_err(|e| format!("tar: {e}"))?;
    }
    Ok(())
}

// ------------------------------------------------------------- flint.deps.mvn

pub struct Mvn {
    pub repos: Vec<String>,
}

impl Default for Mvn {
    fn default() -> Self {
        Mvn {
            // Clojars first because that is where Clojure libraries live;
            // Central because `org.clojure` itself does not.
            repos: vec![
                "https://repo.clojars.org".into(),
                "https://repo1.maven.org/maven2".into(),
            ],
        }
    }
}

fn split_coord(c: &str) -> Result<(String, String), String> {
    let (g, a) = c
        .split_once('/')
        .ok_or_else(|| format!("{c:?} is not group/artifact"))?;
    Ok((g.replace('.', "/"), a.to_string()))
}

impl Mvn {
    pub fn name_static(&self) -> &'static str {
        "flint.deps.mvn"
    }
}

impl Service for Mvn {
    fn name(&self) -> &str {
        "flint.deps.mvn"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![("versions", &[1]), ("pom", &[2]), ("fetch", &[2])]
    }
    fn invoke(&mut self, var: &str, args: &[Val], p: &Policy) -> Answer {
        let mut w = Wire::new();
        let coord = str_arg(args, 0, "group/artifact")?;
        let (gpath, artifact) = split_coord(coord)?;
        match var {
            "versions" => {
                // `maven-metadata.xml`, read with a scan rather than an XML
                // parser: the file is machine-generated and the only thing
                // wanted from it is the `<version>` elements. A parser here
                // would be a dependency for one tag.
                let mut out: Vec<String> = Vec::new();
                for repo in &self.repos {
                    let url = format!("{repo}/{gpath}/{artifact}/maven-metadata.xml");
                    if let Ok(b) = get(&url, p) {
                        let text = String::from_utf8_lossy(&b);
                        for part in text.split("<version>").skip(1) {
                            if let Some(v) = part.split("</version>").next() {
                                out.push(v.trim().to_string());
                            }
                        }
                        break;
                    }
                }
                out.sort();
                out.dedup();
                w.vector(out.len() as u32);
                for v in out {
                    w.string(&v);
                }
            }
            "pom" => {
                let version = str_arg(args, 1, "version")?;
                let mut found = None;
                for repo in &self.repos {
                    let url =
                        format!("{repo}/{gpath}/{artifact}/{version}/{artifact}-{version}.pom");
                    if let Ok(b) = get(&url, p) {
                        found = Some(String::from_utf8_lossy(&b).into_owned());
                        break;
                    }
                }
                match found {
                    Some(t) => {
                        w.string(&t);
                    }
                    None => return Err(format!("no pom for {coord} {version}")),
                }
            }
            "fetch" => {
                let version = str_arg(args, 1, "version")?;
                let dir = cache_dir("mvn", coord, version);
                let stamp = dir.join(".flint-fetched");
                if stamp.exists() {
                    w.string(&dir.display().to_string());
                    return Ok(w);
                }
                let mut hit = None;
                for repo in &self.repos {
                    let url =
                        format!("{repo}/{gpath}/{artifact}/{version}/{artifact}-{version}.jar");
                    if let Ok(b) = get(&url, p) {
                        hit = Some((url, b));
                        break;
                    }
                }
                let (url, body) =
                    hit.ok_or_else(|| format!("no jar for {coord} {version} in any repository"))?;
                let digest = sha256_hex(&body);
                std::fs::create_dir_all(&dir).map_err(|e| format!("{dir:?}: {e}"))?;
                unpack_zip(&body, &dir)?;
                std::fs::write(&stamp, format!("{url}\nsha256:{digest}\n"))
                    .map_err(|e| format!("{stamp:?}: {e}"))?;
                // A jar is a zip and Clojure source sits at its root, so the
                // extracted directory IS the source root.
                w.string(&dir.display().to_string());
            }
            other => return Err(format!("flint.deps.mvn has no {other}")),
        }
        Ok(w)
    }
}

/// Unpack a zip into `dir`, with the same escape refusal `unpack_tgz` has and
/// for the same reason: a zip entry names its own path.
fn unpack_zip(body: &[u8], dir: &Path) -> Result<(), String> {
    let reader = std::io::Cursor::new(body);
    let mut z = zip::ZipArchive::new(reader).map_err(|e| format!("zip: {e}"))?;
    for i in 0..z.len() {
        let mut f = z.by_index(i).map_err(|e| format!("zip: {e}"))?;
        let name = f.name().to_string();
        if name.ends_with('/') {
            continue;
        }
        let out = crate::sys::under(dir, &name)
            .map_err(|m| format!("zip entry {name:?}: {m}"))?;
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("{parent:?}: {e}"))?;
        }
        let mut w = std::fs::File::create(&out).map_err(|e| format!("{out:?}: {e}"))?;
        std::io::copy(&mut f, &mut w).map_err(|e| format!("zip: {e}"))?;
    }
    Ok(())
}

// ------------------------------------------------------------- flint.deps.git

/// Git as a FIRST-CLASS package manager (`doc/decisions/0037`).
///
/// Canonical `deps.edn` treats a git dependency as a URL and a sha: you pin a
/// commit and there is no such thing as asking for a version. That is the one
/// thing this adds, and it is why `:git/version` exists beside the canonical
/// keys rather than instead of them:
///
/// ```clojure
/// {:git/url "https://github.com/org/lib"
///  :git/version "^1.2.0"    ; SEMVER, resolved against tags
///  :git/sha "a1b2c3d"}      ; INTEGRITY, full or prefix
/// ```
///
/// * `:git/version` resolves against the repository's TAGS. `v1.2.3`, `1.2.3`
///   and `release-1.2.3` are all recognised, because tagging conventions differ
///   and refusing three of the four common ones would make the feature unusable
///   on real repositories.
/// * `:git/sha` on top is INTEGRITY and not identity: the resolved tag must
///   point at that commit or the fetch is refused. A prefix is compared as a
///   prefix, so the familiar 7-character form works.
/// * `:git/tag` keeps canonical behaviour exactly. Somebody's existing
///   `deps.edn` has to keep meaning what it meant.
///
/// ## Why `git` the program, and not a git crate
///
/// `gix` is a large dependency tree for what this needs, which is two
/// operations: list the remote's refs, and get one commit's tree. `git` is
/// present wherever a developer fetches source from git at all -- the case this
/// serves -- and shelling out to it keeps the binary small enough to stay the
/// thing `0021` claims it is. This is a deliberate exception to "pull in the
/// crates", made once, with the reason recorded rather than left as an
/// inconsistency; if `git` turns out to be absent in a real environment, the
/// crate is the answer and the surface here does not change.
pub struct Git;

impl Git {
    pub fn name_static(&self) -> &'static str {
        "flint.deps.git"
    }
}

/// What to tell somebody who has no `git`.
///
/// A missing tool is the one error where "not found" is useless on its own: the
/// reader knows it is missing, and what they need is the line to type. So the
/// platform is detected and the message says it -- and when the platform is one
/// nobody has written a line for, it says where to go rather than guessing.
///
/// flint shells out to `git` deliberately (`doc/decisions/0037`): the two
/// operations it needs do not justify `gix`'s dependency tree. That is a
/// reasonable trade only if the failure explains itself, which is what this is.
fn no_git_message(e: &std::io::Error) -> String {
    // No line continuations in these literals: `\` at end of line keeps the
    // SOURCE indentation in the output, so a carefully laid out message comes
    // out indented by however deep the function happened to be.
    let how = if cfg!(target_os = "macos") {
        "  xcode-select --install    (Apple's, no Homebrew needed)\n  brew install git          (if you use Homebrew)"
    } else if cfg!(target_os = "windows") {
        "  winget install Git.Git\n  or download it from https://git-scm.com/download/win"
    } else if cfg!(target_os = "linux") {
        "  apt install git           (Debian, Ubuntu)\n  dnf install git           (Fedora, RHEL)\n  pacman -S git             (Arch)\n  apk add git               (Alpine)"
    } else {
        "  https://git-scm.com/downloads lists a package for every platform"
    };
    let mut m = String::new();
    m.push_str("a git dependency needs the `git` program, and it is not on your PATH.\n\n");
    m.push_str(how);
    m.push_str("\n\nflint runs `git` rather than embedding a git implementation: what it needs\n");
    m.push_str("is two operations -- listing a repository's tags, and fetching one commit --\n");
    m.push_str("and a whole git library is a large thing to carry for two.\n\n");
    m.push_str(&format!("(the underlying error was: {e})"));
    m
}

fn git(args: &[&str], cwd: Option<&Path>) -> Result<String, String> {
    let mut c = std::process::Command::new("git");
    c.args(args);
    if let Some(d) = cwd {
        c.current_dir(d);
    }
    let out = c.output().map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            no_git_message(&e)
        } else {
            format!("could not run git: {e}")
        }
    })?;
    if !out.status.success() {
        return Err(format!(
            "git {}: {}",
            args.join(" "),
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// A tag, and the version it means. `None` when the tag is not a version.
///
/// The four conventions that actually occur, and nothing more clever: a
/// leading `v`, a bare number, and a `name-1.2.3` suffix. A regex would let
/// this match things nobody meant.
fn tag_version(tag: &str) -> Option<semver::Version> {
    let t = tag.trim();
    if let Ok(v) = semver::Version::parse(t.strip_prefix('v').unwrap_or(t)) {
        return Some(v);
    }
    let after = t.rsplit_once('-').map(|(_, r)| r)?;
    semver::Version::parse(after.strip_prefix('v').unwrap_or(after)).ok()
}

impl Service for Git {
    fn name(&self) -> &str {
        "flint.deps.git"
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        vec![
            ("tags", &[1]),
            ("resolve", &[2]),
            ("resolve-tag", &[2]),
            ("fetch", &[2]),
        ]
    }
    fn invoke(&mut self, var: &str, args: &[Val], p: &Policy) -> Answer {
        let url = str_arg(args, 0, "url")?;
        if !p.deps_allows(url) {
            return Err(format!("{url} is not in this program's :deps allowlist"));
        }
        let mut w = Wire::new();
        match var {
            // Every tag and the commit it points at, from ONE `ls-remote` --
            // no clone, so asking what versions exist costs a round trip rather
            // than a checkout.
            "tags" => {
                let out = git(&["ls-remote", "--tags", url], None)?;
                let rows = parse_ls_remote(&out);
                w.vector(rows.len() as u32);
                for (tag, sha) in rows {
                    w.map(2);
                    w.keyword(None, "tag");
                    w.string(&tag);
                    w.keyword(None, "sha");
                    w.string(&sha);
                }
            }
            // EVERY tag matching the range, oldest first, with its sha and the
            // version it parsed to. The caller picks -- same rule as npm.
            "resolve" => {
                let range = str_arg(args, 1, "range")?;
                let req = semver::VersionReq::parse(range)
                    .map_err(|e| format!("{range:?} is not a semver range: {e}"))?;
                let out = git(&["ls-remote", "--tags", url], None)?;
                let mut hits: Vec<(semver::Version, String, String)> = Vec::new();
                for (tag, sha) in parse_ls_remote(&out) {
                    if let Some(v) = tag_version(&tag) {
                        if req.matches(&v) {
                            hits.push((v, tag, sha));
                        }
                    }
                }
                hits.sort_by(|a, b| a.0.cmp(&b.0));
                w.vector(hits.len() as u32);
                for (v, tag, sha) in hits {
                    w.map(3);
                    w.keyword(None, "version");
                    w.string(&v.to_string());
                    w.keyword(None, "tag");
                    w.string(&tag);
                    w.keyword(None, "sha");
                    w.string(&sha);
                }
            }
            // CANONICAL `:git/tag`: the sha a named tag points at, unchanged.
            "resolve-tag" => {
                let tag = str_arg(args, 1, "tag")?;
                let out = git(&["ls-remote", "--tags", url], None)?;
                let hit = parse_ls_remote(&out).into_iter().find(|(t, _)| t == tag);
                match hit {
                    Some((_, sha)) => {
                        w.string(&sha);
                    }
                    None => return Err(format!("{url} has no tag {tag}")),
                }
            }
            // A SHALLOW fetch of one sha rather than a clone: it is the cheap
            // shape, and it cannot silently give a different commit later the
            // way a branch clone can.
            "fetch" => {
                let sha = str_arg(args, 1, "sha")?;
                let dir = cache_dir("git", url, sha);
                let stamp = dir.join(".flint-fetched");
                if stamp.exists() {
                    w.string(&dir.display().to_string());
                    return Ok(w);
                }
                std::fs::create_dir_all(&dir).map_err(|e| format!("{dir:?}: {e}"))?;
                git(&["init", "-q"], Some(&dir))?;
                // `remote add` failing because it already exists is not a
                // failure: a re-fetch into a half-finished directory is the
                // case this has to survive.
                let _ = git(&["remote", "add", "origin", url], Some(&dir));
                git(&["fetch", "-q", "--depth", "1", "origin", sha], Some(&dir))?;
                git(&["checkout", "-q", "FETCH_HEAD"], Some(&dir))?;
                std::fs::write(&stamp, format!("{url} {sha}\n"))
                    .map_err(|e| format!("{stamp:?}: {e}"))?;
                w.string(&dir.display().to_string());
            }
            other => return Err(format!("flint.deps.git has no {other}")),
        }
        Ok(w)
    }
}

/// `ls-remote --tags` output to `[(tag, sha)]`.
///
/// `^{}` entries are DROPPED and that is the whole subtlety here. An annotated
/// tag is an object of its own, and `ls-remote` lists both the tag object and
/// the commit it dereferences to, as `refs/tags/v1.0` and `refs/tags/v1.0^{}`.
/// The commit is the one a checkout wants; taking the first line for a tag
/// would pin the tag object, whose sha is not the commit's, and the integrity
/// check would then fail against a correct repository.
fn parse_ls_remote(out: &str) -> Vec<(String, String)> {
    let mut best: Vec<(String, String)> = Vec::new();
    for line in out.lines() {
        let Some((sha, r)) = line.split_once('\t') else { continue };
        let Some(name) = r.strip_prefix("refs/tags/") else { continue };
        let (tag, deref) = match name.strip_suffix("^{}") {
            Some(t) => (t, true),
            None => (name, false),
        };
        match best.iter_mut().find(|(t, _)| t == tag) {
            // A dereferenced entry REPLACES the tag object's.
            Some(slot) if deref => slot.1 = sha.to_string(),
            Some(_) => {}
            None => best.push((tag.to_string(), sha.to_string())),
        }
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tag_conventions_that_actually_occur() {
        assert_eq!(tag_version("v1.2.3"), Some(semver::Version::new(1, 2, 3)));
        assert_eq!(tag_version("1.2.3"), Some(semver::Version::new(1, 2, 3)));
        assert_eq!(tag_version("release-1.2.3"), Some(semver::Version::new(1, 2, 3)));
        assert_eq!(tag_version("lib-v1.2.3"), Some(semver::Version::new(1, 2, 3)));
        assert_eq!(tag_version("nightly"), None);
        assert_eq!(tag_version(""), None);
    }

    #[test]
    fn an_annotated_tag_resolves_to_its_commit_and_not_to_the_tag_object() {
        // The tag object is listed first and the commit second, with `^{}`.
        // Taking the first would pin an object no checkout wants, and the sha
        // integrity check would then fail against a perfectly good repository.
        let out = "aaaa\trefs/tags/v1.0.0\nbbbb\trefs/tags/v1.0.0^{}\n";
        assert_eq!(parse_ls_remote(out), vec![("v1.0.0".into(), "bbbb".into())]);
    }

    #[test]
    fn a_lightweight_tag_has_only_one_line_and_keeps_it() {
        let out = "cccc\trefs/tags/v2.0.0\n";
        assert_eq!(parse_ls_remote(out), vec![("v2.0.0".into(), "cccc".into())]);
    }

    #[test]
    fn heads_and_other_refs_are_not_tags() {
        let out = "dddd\trefs/heads/main\neeee\trefs/tags/v1.0.0\n";
        assert_eq!(parse_ls_remote(out), vec![("v1.0.0".into(), "eeee".into())]);
    }
}
