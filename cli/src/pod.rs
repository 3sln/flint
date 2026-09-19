//! `flint.deps.pod`: babashka pods as virtual namespaces (`DECISIONS.md#system-namespaces-and-deps`).
//!
//! A pod is a subprocess speaking bencode over stdio. It is one implementation
//! of the SAME interface `flint.sys.fs` implements -- `{:op :invoke}`,
//! `{:op :get}`, `{:op :list}` -- which is the whole reason `workspace-capabilities` made the var
//! list optional and sourced from `:list`: a booted pod can be asked what it
//! holds, and a build that boots one gets the same compile-time checking as any
//! other namespace.
//!
//! ## The protocol, and what is implemented
//!
//! babashka's pod protocol is bencode over the child's stdin and stdout:
//!
//! ```text
//! -> {"op" "describe"}          <- {"namespaces" [...] "format" "json"}
//! -> {"op" "invoke" "var" ".."  <- {"value" ".." "status" ["done"]}
//!     "args" "[...]" "id" ".."}    or {"ex-message" ".." "status" ["done" "error"]}
//! -> {"op" "shutdown"}
//! ```
//!
//! `format` is `json` or `edn`, and this speaks **json only**. A pod declaring
//! `edn` is REFUSED by name rather than half-served: flint has an EDN reader in
//! the guest and none here, and pretending otherwise would fail at the first
//! keyword.
//!
//! ## Macros: no
//!
//! A pod may declare a var as a macro. Serving one means invoking the pod at
//! COMPILE time and letting its output into the program, which is a bigger step
//! than reading a description -- `:list` reads a description, a macro runs the
//! pod's code. babashka's own pods do not do it. Refused here rather than left
//! unmentioned.

use crate::policy::Policy;
use crate::sys::{Answer, Service};
use flint_rt::codec::{Val, Wire};
use std::io::{BufReader, Read, Write};
use std::process::{Child, Command, Stdio};

// --- bencode ---------------------------------------------------------------
//
// Written rather than depended on. Bencode is four forms and the encoder is
// twenty lines; a crate for it would be a dependency in a binary that counts
// them.

#[derive(Debug, Clone, PartialEq)]
pub enum Ben {
    Str(Vec<u8>),
    Int(i64),
    List(Vec<Ben>),
    Dict(Vec<(Vec<u8>, Ben)>),
}

impl Ben {
    pub fn s(v: &str) -> Ben {
        Ben::Str(v.as_bytes().to_vec())
    }
    pub fn get(&self, k: &str) -> Option<&Ben> {
        match self {
            Ben::Dict(es) => es.iter().find(|(kk, _)| kk == k.as_bytes()).map(|(_, v)| v),
            _ => None,
        }
    }
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Ben::Str(b) => core::str::from_utf8(b).ok(),
            _ => None,
        }
    }
    pub fn encode(&self, out: &mut Vec<u8>) {
        match self {
            Ben::Str(b) => {
                out.extend_from_slice(b.len().to_string().as_bytes());
                out.push(b':');
                out.extend_from_slice(b);
            }
            Ben::Int(n) => {
                out.push(b'i');
                out.extend_from_slice(n.to_string().as_bytes());
                out.push(b'e');
            }
            Ben::List(xs) => {
                out.push(b'l');
                for x in xs {
                    x.encode(out);
                }
                out.push(b'e');
            }
            Ben::Dict(es) => {
                out.push(b'd');
                for (k, v) in es {
                    Ben::Str(k.clone()).encode(out);
                    v.encode(out);
                }
                out.push(b'e');
            }
        }
    }
}

/// Read one bencode value from a stream.
///
/// Byte at a time, because a bencode value is self-delimiting and there is no
/// length prefix to read ahead with -- buffering more would mean owning a
/// buffer across calls and getting the boundary wrong is how a protocol reader
/// desynchronises.
pub fn read_ben<R: Read>(r: &mut R) -> Result<Ben, String> {
    fn byte<R: Read>(r: &mut R) -> Result<u8, String> {
        let mut b = [0u8; 1];
        r.read_exact(&mut b).map_err(|e| format!("pod: {e}"))?;
        Ok(b[0])
    }
    fn go<R: Read>(r: &mut R, first: u8) -> Result<Ben, String> {
        match first {
            b'i' => {
                let mut s = String::new();
                loop {
                    let c = byte(r)?;
                    if c == b'e' {
                        break;
                    }
                    s.push(c as char);
                }
                s.parse().map(Ben::Int).map_err(|e| format!("pod: {e}"))
            }
            b'l' => {
                let mut xs = Vec::new();
                loop {
                    let c = byte(r)?;
                    if c == b'e' {
                        return Ok(Ben::List(xs));
                    }
                    xs.push(go(r, c)?);
                }
            }
            b'd' => {
                let mut es = Vec::new();
                loop {
                    let c = byte(r)?;
                    if c == b'e' {
                        return Ok(Ben::Dict(es));
                    }
                    let Ben::Str(k) = go(r, c)? else {
                        return Err("pod: a dict key was not a string".into());
                    };
                    let c2 = byte(r)?;
                    es.push((k, go(r, c2)?));
                }
            }
            d if d.is_ascii_digit() => {
                let mut n = (d - b'0') as usize;
                loop {
                    let c = byte(r)?;
                    if c == b':' {
                        break;
                    }
                    if !c.is_ascii_digit() {
                        return Err("pod: a string length was not a number".into());
                    }
                    n = n * 10 + (c - b'0') as usize;
                }
                // Bounded: a length is the wire saying how much to allocate, and
                // a corrupt one must not be believed to the tune of a gigabyte.
                if n > 64 * 1024 * 1024 {
                    return Err(format!("pod: a string claimed {n} bytes"));
                }
                let mut b = vec![0u8; n];
                r.read_exact(&mut b).map_err(|e| format!("pod: {e}"))?;
                Ok(Ben::Str(b))
            }
            other => Err(format!("pod: unexpected byte {other:?} in bencode")),
        }
    }
    let f = byte(r)?;
    go(r, f)
}

// --- the pod ---------------------------------------------------------------

pub struct Pod {
    ns: String,
    child: Child,
    reader: BufReader<std::process::ChildStdout>,
    vars: Vec<String>,
    next_id: u64,
}

impl Pod {
    /// Boot `program` and ask it what it holds.
    pub fn boot(ns: &str, program: &str, args: &[String]) -> Result<Pod, String> {
        let mut child = Command::new(program)
            .args(args)
            .env("BABASHKA_POD", "true")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| format!("could not start the pod {program:?}: {e}"))?;
        let out = child.stdout.take().ok_or("pod: no stdout")?;
        let mut p = Pod {
            ns: ns.to_string(),
            child,
            reader: BufReader::new(out),
            vars: Vec::new(),
            next_id: 1,
        };
        p.describe()?;
        Ok(p)
    }

    fn send(&mut self, d: Ben) -> Result<(), String> {
        let mut buf = Vec::new();
        d.encode(&mut buf);
        let stdin = self.child.stdin.as_mut().ok_or("pod: no stdin")?;
        stdin.write_all(&buf).map_err(|e| format!("pod: {e}"))?;
        stdin.flush().map_err(|e| format!("pod: {e}"))
    }

    fn describe(&mut self) -> Result<(), String> {
        self.send(Ben::Dict(vec![(b"op".to_vec(), Ben::s("describe"))]))?;
        let r = read_ben(&mut self.reader)?;
        // FORMAT FIRST. A pod that speaks edn is refused by name rather than
        // half-served: there is no EDN reader on this side, and pretending
        // would fail at the first keyword with a message about nothing.
        if let Some(f) = r.get("format").and_then(|v| v.as_str()) {
            if f != "json" {
                return Err(format!(
                    "the pod {} speaks {f:?}; flint speaks json to pods",
                    self.ns
                ));
            }
        }
        let Some(Ben::List(nss)) = r.get("namespaces") else {
            return Err("pod: describe carried no namespaces".into());
        };
        for n in nss {
            let name = n.get("name").and_then(|v| v.as_str()).unwrap_or("");
            if name != self.ns {
                continue;
            }
            if let Some(Ben::List(vs)) = n.get("vars") {
                for v in vs {
                    // A MACRO is skipped, and that is deliberate: serving one
                    // means running the pod at compile time and letting its
                    // output into the program. babashka's pods do not, and
                    // neither does this.
                    let is_macro = v
                        .get("meta")
                        .and_then(|m| m.as_str())
                        .map(|s| s.contains(":macro true"))
                        .unwrap_or(false);
                    if is_macro {
                        continue;
                    }
                    if let Some(nm) = v.get("name").and_then(|x| x.as_str()) {
                        self.vars.push(nm.to_string());
                    }
                }
            }
        }
        Ok(())
    }

    pub fn var_names(&self) -> &[String] {
        &self.vars
    }
}

impl Drop for Pod {
    fn drop(&mut self) {
        // SHUTDOWN, then kill. A pod that is asked to stop can flush and close
        // what it owns; one that is only killed cannot, and a pod holding a
        // database connection is the ordinary case.
        let _ = self.send(Ben::Dict(vec![(b"op".to_vec(), Ben::s("shutdown"))]));
        let _ = self.child.wait();
    }
}

impl Service for Pod {
    fn name(&self) -> &str {
        &self.ns
    }
    fn vars(&self) -> Vec<(&'static str, &'static [u32])> {
        // EMPTY, and that is honest rather than lazy. This trait answers
        // `'static` names because every other service's surface is known at
        // compile time; a pod's is discovered at run time, so it cannot. The
        // var list a BUILD needs comes from `var_names` and is threaded into
        // the compile spec, which is where it matters -- `workspace-capabilities` says the list
        // buys checking, not codegen.
        Vec::new()
    }
    fn invoke(&mut self, var: &str, args: &[Val], _p: &Policy) -> Answer {
        if !self.vars.iter().any(|v| v == var) {
            return Err(format!("the pod {} has no {var}", self.ns));
        }
        let id = self.next_id;
        self.next_id += 1;
        // ARGUMENTS CROSS AS JSON, because that is the format declared at
        // describe time. A value the codec carries and JSON does not -- a set, a
        // keyword key -- is lossy here, and that is the pod protocol's
        // limitation rather than flint's (`bridges`).
        let json = to_json(args);
        self.send(Ben::Dict(vec![
            (b"op".to_vec(), Ben::s("invoke")),
            (b"id".to_vec(), Ben::s(&id.to_string())),
            (b"var".to_vec(), Ben::s(&format!("{}/{}", self.ns, var))),
            (b"args".to_vec(), Ben::s(&json)),
        ]))?;
        loop {
            let r = read_ben(&mut self.reader)?;
            let rid = r.get("id").and_then(|v| v.as_str()).unwrap_or("");
            if rid != id.to_string() {
                // NOT OURS. A pod may emit output for another call; skipping is
                // right and silently taking it would be the demultiplexing bug
                // `flint.rpc` exists to avoid.
                continue;
            }
            if let Some(msg) = r.get("ex-message").and_then(|v| v.as_str()) {
                return Err(format!("{}/{var}: {msg}", self.ns));
            }
            let value = r.get("value").and_then(|v| v.as_str()).unwrap_or("null");
            return from_json(value);
        }
    }
}

/// Wire values to a JSON array, for the pod's `args`.
fn to_json(args: &[Val]) -> String {
    fn one(v: &Val) -> serde_json::Value {
        use serde_json::Value as J;
        match v {
            Val::Nil => J::Null,
            Val::Bool(b) => J::Bool(*b),
            Val::Int(n) => J::from(*n),
            Val::Float(f) => serde_json::Number::from_f64(*f).map(J::Number).unwrap_or(J::Null),
            Val::Str(s) => J::String(s.clone()),
            // A keyword crosses as its NAME. JSON has no keyword, and this is
            // the lossy edge the protocol's format choice creates.
            Val::Keyword(_, n) | Val::Symbol(_, n) => J::String(n.clone()),
            Val::Vector(xs) | Val::List(xs) | Val::Set(xs) => {
                J::Array(xs.iter().map(one).collect())
            }
            // THE VALUE, WITHOUT ITS METADATA. JSON has nowhere to put it, and
            // a pod's arguments are JSON -- so this is the same lossy edge the
            // keyword arm above names, for the same reason. Dropping it beats
            // inventing a representation a pod would have to know about.
            Val::Meta(_, v) => one(v),
            Val::Map(es) => {
                let mut m = serde_json::Map::new();
                for (k, v) in es {
                    let key = match k {
                        Val::Str(s) => s.clone(),
                        Val::Keyword(_, n) | Val::Symbol(_, n) => n.clone(),
                        other => format!("{other:?}"),
                    };
                    m.insert(key, one(v));
                }
                J::Object(m)
            }
            Val::Bytes(_) | Val::Port(_) | Val::Opaque(_, _) | Val::Tagged(_, _) => J::Null,
        }
    }
    serde_json::Value::Array(args.iter().map(one).collect()).to_string()
}

/// The pod's JSON answer, as a wire value.
fn from_json(text: &str) -> Answer {
    let v: serde_json::Value =
        serde_json::from_str(text).map_err(|e| format!("pod: answer was not JSON: {e}"))?;
    let mut w = Wire::new();
    put_json(&mut w, &v);
    Ok(w)
}

fn put_json(w: &mut Wire, v: &serde_json::Value) {
    use serde_json::Value as J;
    match v {
        J::Null => {
            w.nil();
        }
        J::Bool(b) => {
            w.bool(*b);
        }
        J::Number(n) => {
            if let Some(i) = n.as_i64() {
                w.int(i);
            } else {
                w.float(n.as_f64().unwrap_or(0.0));
            }
        }
        J::String(s) => {
            w.string(s);
        }
        J::Array(xs) => {
            w.vector(xs.len() as u32);
            for x in xs {
                put_json(w, x);
            }
        }
        J::Object(m) => {
            w.map(m.len() as u32);
            for (k, x) in m {
                // KEYS COME BACK AS KEYWORDS, which is what a Clojure caller
                // expects from a map and what `codec.from`'s `keywordizeKeys`
                // does on the other side of the same boundary.
                w.keyword(None, k);
                put_json(w, x);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn enc(b: &Ben) -> Vec<u8> {
        let mut v = Vec::new();
        b.encode(&mut v);
        v
    }

    #[test]
    fn bencode_round_trips() {
        let d = Ben::Dict(vec![
            (b"op".to_vec(), Ben::s("invoke")),
            (b"id".to_vec(), Ben::Int(7)),
            (b"xs".to_vec(), Ben::List(vec![Ben::s("a"), Ben::Int(-1)])),
        ]);
        let bytes = enc(&d);
        let back = read_ben(&mut bytes.as_slice()).expect("reads");
        assert_eq!(back.get("op").and_then(|v| v.as_str()), Some("invoke"));
        assert_eq!(back.get("id"), Some(&Ben::Int(7)));
    }

    #[test]
    fn a_string_length_far_past_the_end_is_refused_not_allocated() {
        assert!(read_ben(&mut b"999999999999:".as_slice()).is_err());
    }

    #[test]
    fn a_truncated_value_is_an_error_rather_than_a_panic() {
        let full = enc(&Ben::Dict(vec![(b"a".to_vec(), Ben::s("bc"))]));
        for n in 0..full.len() {
            assert!(read_ben(&mut &full[..n]).is_err(), "prefix {n}");
        }
    }

    #[test]
    fn json_keys_come_back_as_keywords() {
        let w = from_json(r#"{"a":1}"#).expect("ok");
        let v = flint_rt::codec::parse(w.as_bytes()).expect("parses");
        assert_eq!(v.get("a").and_then(|x| x.as_i64()), Some(1));
    }

    #[test]
    fn a_keyword_argument_crosses_as_its_name_because_json_has_no_keyword() {
        let j = to_json(&[Val::Keyword(None, "abc".into())]);
        assert_eq!(j, r#"["abc"]"#);
    }
}
