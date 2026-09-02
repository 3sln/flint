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
