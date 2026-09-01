//! A value crossing the boundary (`doc/decisions/0025`).
//!
//! The same encoding the JavaScript SDK uses and the same one the runtime
//! reads: a call's arguments, its return, and everything a port carries.
//!
//! It is explicit rather than a conversion because Rust, like JS, cannot say
//! what it means from the shape alone. `1` is an integer or a double; a `Vec`
//! is a vector or a list; a map's keys are strings or keywords. Guessing is
//! right often enough to be a bug, so the constructors say.

use std::collections::BTreeMap;

const K_NIL: u8 = 0;
const K_TRUE: u8 = 1;
const K_FALSE: u8 = 2;
const K_INT: u8 = 3;
const K_DOUBLE: u8 = 4;
const K_STRING: u8 = 5;
const K_KEYWORD: u8 = 6;
const K_SYMBOL: u8 = 7;
const K_VECTOR: u8 = 8;
const K_LIST: u8 = 9;
const K_MAP: u8 = 10;
const K_SET: u8 = 11;
const K_BYTES: u8 = 14;
const K_PORT: u8 = 15;
const K_SENTINEL: u8 = 16;
const K_TAGGED: u8 = 17;
const K_TABLE: u8 = 18;
const NO_NS: u32 = u32::MAX;

#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Nil,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    /// `(namespace, name)`; the namespace is absent for `:foo`.
    Keyword(Option<String>, String),
    Symbol(Option<String>, String),
    Bytes(Vec<u8>),
    Vector(Vec<Value>),
    List(Vec<Value>),
    Set(Vec<Value>),
    /// Ordered pairs rather than a map, because a flint map's keys are not
    /// always strings and order is worth keeping through a round trip.
    Map(Vec<(Value, Value)>),
    /// A live thing, by identity. A host may hand one back; nothing here can
    /// make one from an integer, which is the sandbox rule (`0025`).
    Port(u32),
    Sentinel { host_id: u64, label: String },
    /// A tagged literal (`doc/decisions/0034`): a namespaced symbol and a form.
    /// Its own variant rather than a two-key map, for the reason the type
    /// exists -- a host meeting one must be able to tell it from a map that
    /// happens to have those keys.
    Tagged { tag: (Option<String>, String), form: Box<Value> },
    /// A table (`doc/decisions/0026`), COLUMNAR both on the wire and here.
    ///
    /// Columns rather than rows because that is what the type is for: a host
    /// reading one field should read one run, and rebuilding a map per row to
    /// hand it over would spend on arrival exactly what the sender saved.
    Table {
        /// `(name, type)` in order; the type is one of `:int :double :string
        /// :bool :keyword :any`.
        schema: Vec<(String, String)>,
        /// One entry per column, each `rows` long.
        columns: Vec<Vec<Value>>,
    },
}

impl Value {
    pub fn str(s: impl Into<String>) -> Value {
        Value::Str(s.into())
    }
    /// `kw("a")` is `:a`; `kw("my.ns/a")` is `:my.ns/a`.
    pub fn kw(s: &str) -> Value {
        match s.split_once('/') {
            Some((ns, name)) => Value::Keyword(Some(ns.into()), name.into()),
            None => Value::Keyword(None, s.into()),
        }
    }
    pub fn map(entries: impl IntoIterator<Item = (Value, Value)>) -> Value {
        Value::Map(entries.into_iter().collect())
    }
    /// A map with keyword keys, which is what most Clojure code wants.
    pub fn kwmap(entries: impl IntoIterator<Item = (&'static str, Value)>) -> Value {
        Value::Map(entries.into_iter().map(|(k, v)| (Value::kw(k), v)).collect())
    }

    /// The value at a key, if this is a map with that key.
    pub fn get(&self, key: &str) -> Option<&Value> {
        let want = if let Some(k) = key.strip_prefix(':') { Value::kw(k) } else { Value::str(key) };
        match self {
            Value::Map(entries) => entries.iter().find(|(k, _)| *k == want).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::Str(s) => Some(s),
            _ => None,
        }
    }
    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Value::Int(i) => Some(*i),
            _ => None,
        }
    }

    // --- encoding ----------------------------------------------------------

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        self.write(&mut out);
        out
    }

    fn write(&self, out: &mut Vec<u8>) {
        fn put_u32(out: &mut Vec<u8>, n: u32) {
            out.extend_from_slice(&n.to_le_bytes());
        }
        fn put_str(out: &mut Vec<u8>, s: &str) {
            put_u32(out, s.len() as u32);
            out.extend_from_slice(s.as_bytes());
        }
        fn put_ident(out: &mut Vec<u8>, ns: &Option<String>, name: &str) {
            match ns {
                Some(s) => put_str(out, s),
                None => put_u32(out, NO_NS),
            }
            put_str(out, name);
        }
        match self {
            Value::Nil => out.push(K_NIL),
            Value::Bool(true) => out.push(K_TRUE),
            Value::Bool(false) => out.push(K_FALSE),
            Value::Int(i) => {
                out.push(K_INT);
                out.extend_from_slice(&i.to_le_bytes());
            }
            Value::Float(f) => {
                out.push(K_DOUBLE);
                out.extend_from_slice(&f.to_bits().to_le_bytes());
            }
            Value::Str(s) => {
                out.push(K_STRING);
                put_str(out, s);
            }
            Value::Keyword(ns, n) => {
                out.push(K_KEYWORD);
                put_ident(out, ns, n);
            }
            Value::Symbol(ns, n) => {
                out.push(K_SYMBOL);
                put_ident(out, ns, n);
            }
            Value::Bytes(b) => {
                out.push(K_BYTES);
                put_u32(out, b.len() as u32);
                out.extend_from_slice(b);
            }
            Value::Vector(items) | Value::List(items) | Value::Set(items) => {
                out.push(match self {
                    Value::Vector(_) => K_VECTOR,
                    Value::List(_) => K_LIST,
                    _ => K_SET,
                });
                put_u32(out, items.len() as u32);
                for i in items {
                    i.write(out);
                }
            }
            Value::Map(entries) => {
                out.push(K_MAP);
                put_u32(out, entries.len() as u32);
                for (k, v) in entries {
                    k.write(out);
                    v.write(out);
                }
            }
            Value::Port(id) => {
                out.push(K_PORT);
                put_u32(out, *id);
            }
            Value::Sentinel { host_id, label } => {
                out.push(K_SENTINEL);
                out.extend_from_slice(&host_id.to_le_bytes());
                put_str(out, label);
            }
            Value::Tagged { tag, form } => {
                out.push(K_TAGGED);
                out.push(K_SYMBOL);
                match &tag.0 {
                    Some(ns) => put_str(out, ns),
                    None => put_u32(out, u32::MAX),
                }
                put_str(out, &tag.1);
                form.write(out);
            }
            Value::Table { schema, columns } => {
                out.push(K_TABLE);
                put_u32(out, schema.len() as u32);
                for (name, ty) in schema {
                    out.push(K_KEYWORD);
                    put_u32(out, u32::MAX);
                    put_str(out, name);
                    out.push(K_KEYWORD);
                    put_u32(out, u32::MAX);
                    put_str(out, ty);
                }
                let rows = columns.first().map(|c| c.len()).unwrap_or(0);
                put_u32(out, rows as u32);
                for col in columns {
                    for v in col {
                        v.write(out);
                    }
                }
            }
        }
    }

    // --- decoding ----------------------------------------------------------

    pub fn decode(bytes: &[u8]) -> Result<Value, String> {
        let mut i = 0usize;
        let v = read(bytes, &mut i, 0)?;
        Ok(v)
    }
}

fn take<'a>(b: &'a [u8], i: &mut usize, n: usize) -> Result<&'a [u8], String> {
    let e = *i + n;
    let s = b.get(*i..e).ok_or("the encoding ends mid-value")?;
    *i = e;
    Ok(s)
}
fn read_u32(b: &[u8], i: &mut usize) -> Result<u32, String> {
    let s = take(b, i, 4)?;
    Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}
fn read_u64(b: &[u8], i: &mut usize) -> Result<u64, String> {
    let s = take(b, i, 8)?;
    let mut a = [0u8; 8];
    a.copy_from_slice(s);
    Ok(u64::from_le_bytes(a))
}
fn read_str(b: &[u8], i: &mut usize) -> Result<Option<String>, String> {
    let n = read_u32(b, i)?;
    if n == NO_NS {
        return Ok(None);
    }
    let s = take(b, i, n as usize)?;
    Ok(Some(
        std::str::from_utf8(s).map_err(|_| "a string in the encoding is not UTF-8")?.to_string(),
    ))
}

fn read(b: &[u8], i: &mut usize, depth: u32) -> Result<Value, String> {
    if depth > 128 {
        return Err("value nested too deeply to decode".into());
    }
    let tag = *b.get(*i).ok_or("the encoding ends mid-value")?;
    *i += 1;
    Ok(match tag {
        K_NIL => Value::Nil,
        K_TRUE => Value::Bool(true),
        K_FALSE => Value::Bool(false),
        K_INT => Value::Int(read_u64(b, i)? as i64),
        K_DOUBLE => Value::Float(f64::from_bits(read_u64(b, i)?)),
        K_STRING => Value::Str(read_str(b, i)?.ok_or("a string cannot be absent")?),
        K_KEYWORD | K_SYMBOL => {
            let ns = read_str(b, i)?;
            let name = read_str(b, i)?.ok_or("a name cannot be absent")?;
            if tag == K_KEYWORD { Value::Keyword(ns, name) } else { Value::Symbol(ns, name) }
        }
        K_BYTES => {
            let n = read_u32(b, i)? as usize;
            Value::Bytes(take(b, i, n)?.to_vec())
        }
        K_VECTOR | K_LIST | K_SET => {
            let n = read_u32(b, i)? as usize;
            let mut items = Vec::with_capacity(n.min(1024));
            for _ in 0..n {
                items.push(read(b, i, depth + 1)?);
            }
            match tag {
                K_VECTOR => Value::Vector(items),
                K_LIST => Value::List(items),
                _ => Value::Set(items),
            }
        }
        K_MAP => {
            let n = read_u32(b, i)? as usize;
            let mut entries = Vec::with_capacity(n.min(1024));
            for _ in 0..n {
                let k = read(b, i, depth + 1)?;
                let v = read(b, i, depth + 1)?;
                entries.push((k, v));
            }
            Value::Map(entries)
        }
        K_PORT => Value::Port(read_u32(b, i)?),
        K_SENTINEL => {
            let host_id = read_u64(b, i)?;
            let label = read_str(b, i)?.ok_or("a label cannot be absent")?;
            Value::Sentinel { host_id, label }
        }
        K_TAGGED => {
            let t = read(b, i, depth + 1)?;
            let tag = match t {
                Value::Symbol(ns, name) => (ns, name),
                _ => return Err(String::from("a tagged literal's tag must be a symbol")),
            };
            let form = read(b, i, depth + 1)?;
            Value::Tagged { tag, form: Box::new(form) }
        }
        K_TABLE => {
            let ncols = read_u32(b, i)? as usize;
            let mut schema = Vec::with_capacity(ncols);
            for _ in 0..ncols {
                let name = match read(b, i, depth + 1)? {
                    Value::Keyword(_, n) => n,
                    _ => return Err(String::from("a column name must be a keyword")),
                };
                let ty = match read(b, i, depth + 1)? {
                    Value::Keyword(_, n) => n,
                    _ => return Err(String::from("a column type must be a keyword")),
                };
                schema.push((name, ty));
            }
            let rows = read_u32(b, i)? as usize;
            let mut columns = Vec::with_capacity(ncols);
            for _ in 0..ncols {
                let mut col = Vec::with_capacity(rows);
                for _ in 0..rows {
                    col.push(read(b, i, depth + 1)?);
                }
                columns.push(col);
            }
            Value::Table { schema, columns }
        }
        other => return Err(format!("unknown tag {other} in the encoding")),
    })
}

/// A convenience for the common shapes, matching the JS `codec.from`.
impl From<i64> for Value {
    fn from(v: i64) -> Value { Value::Int(v) }
}
impl From<&str> for Value {
    fn from(v: &str) -> Value { Value::Str(v.into()) }
}
impl From<String> for Value {
    fn from(v: String) -> Value { Value::Str(v) }
}
impl From<bool> for Value {
    fn from(v: bool) -> Value { Value::Bool(v) }
}
impl From<f64> for Value {
    fn from(v: f64) -> Value { Value::Float(v) }
}
impl<T: Into<Value>> From<Vec<T>> for Value {
    fn from(v: Vec<T>) -> Value { Value::Vector(v.into_iter().map(Into::into).collect()) }
}
impl<T: Into<Value>> From<Option<T>> for Value {
    fn from(v: Option<T>) -> Value {
        match v {
            Some(x) => x.into(),
            None => Value::Nil,
        }
    }
}
impl<T: Into<Value>> From<BTreeMap<String, T>> for Value {
    fn from(v: BTreeMap<String, T>) -> Value {
        Value::Map(v.into_iter().map(|(k, x)| (Value::Str(k), x.into())).collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_shape_survives_a_round_trip() {
        let cases = vec![
            Value::Nil,
            Value::Bool(true),
            Value::Int(-42),
            Value::Float(1.5),
            Value::str("hello"),
            Value::kw("a"),
            Value::kw("my.ns/a"),
            Value::Bytes(vec![1, 2, 255]),
            Value::Vector(vec![Value::Int(1), Value::str("two")]),
            Value::Set(vec![Value::Int(1)]),
            Value::kwmap([("a", Value::Int(1)), ("b", Value::Vector(vec![Value::Nil]))]),
            Value::Sentinel { host_id: 7, label: "fs".into() },
        ];
        for v in cases {
            let out = Value::decode(&v.encode()).expect("decode");
            assert_eq!(v, out, "a value did not survive the round trip");
        }
    }

    #[test]
    fn a_short_encoding_is_refused() {
        assert!(Value::decode(&[]).is_err());
        assert!(Value::decode(&[K_INT, 1]).is_err());
        assert!(Value::decode(&[200]).is_err());
    }
}


/// Printed the way flint prints it.
///
/// Not `Debug`: this is the reader's form, so a string is quoted and a keyword
/// carries its colon, and a round trip through flint's reader gives the value
/// back. `Debug` is the Rust programmer's form and stays what derive made it.
impl std::fmt::Display for Value {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        fn seq(f: &mut std::fmt::Formatter<'_>, open: &str, xs: &[Value], close: &str)
            -> std::fmt::Result
        {
            f.write_str(open)?;
            for (i, x) in xs.iter().enumerate() {
                if i > 0 { f.write_str(" ")? }
                write!(f, "{x}")?;
            }
            f.write_str(close)
        }
        fn qualified(f: &mut std::fmt::Formatter<'_>, ns: &Option<String>, n: &str)
            -> std::fmt::Result
        {
            match ns {
                Some(ns) => write!(f, "{ns}/{n}"),
                None => f.write_str(n),
            }
        }
        match self {
            Value::Nil => f.write_str("nil"),
            Value::Bool(b) => write!(f, "{b}"),
            Value::Int(i) => write!(f, "{i}"),
            Value::Float(x) => {
                if x.fract() == 0.0 && x.is_finite() { write!(f, "{x:.1}") } else { write!(f, "{x}") }
            }
            Value::Str(s) => write!(f, "{s:?}"),
            Value::Keyword(ns, n) => { f.write_str(":")?; qualified(f, ns, n) }
            Value::Symbol(ns, n) => qualified(f, ns, n),
            // No reader literal, so this prints what it IS rather than
            // something that would read back as a different thing.
            Value::Bytes(b) => write!(f, "#bytes[{}]", b.len()),
            Value::Vector(xs) => seq(f, "[", xs, "]"),
            Value::List(xs) => seq(f, "(", xs, ")"),
            Value::Set(xs) => seq(f, "#{", xs, "}"),
            Value::Map(kvs) => {
                f.write_str("{")?;
                for (i, (k, v)) in kvs.iter().enumerate() {
                    if i > 0 { f.write_str(", ")? }
                    write!(f, "{k} {v}")?;
                }
                f.write_str("}")
            }
            // An identity in one process, and deliberately not readable: a
            // printed port that read back as a port would be authority nobody
            // granted (`doc/decisions/0025`).
            Value::Port(id) => write!(f, "#port[{id}]"),
            Value::Sentinel { label, .. } => write!(f, "#sentinel[{label}]"),
            Value::Tagged { tag, form } => {
                f.write_str("#")?;
                qualified(f, &tag.0, &tag.1)?;
                write!(f, " {form}")
            }
            // The SCHEMA and the row count, not the rows. A host printing a
            // million-row table in full is not something anyone reads, and the
            // readable form is `pr-str`'s job on the guest side.
            Value::Table { schema, columns } => {
                let rows = columns.first().map(|c| c.len()).unwrap_or(0);
                f.write_str("#flint/table {:schema [")?;
                for (i, (n, t)) in schema.iter().enumerate() {
                    if i > 0 { f.write_str(" ")? }
                    write!(f, "[:{n} :{t}]")?;
                }
                write!(f, "] :rows {rows}}}")
            }
        }
    }
}
