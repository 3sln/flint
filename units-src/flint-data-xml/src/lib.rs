//! `flint.data.xml` — the parser half, adapted from `xmlparser`.
//!
//! `xmlparser` is a *tokenizer*: it hands back `ElementStart`, `Attribute`,
//! `ElementEnd`, `Text` and so on, and leaves nesting to the caller. That is
//! what we want — we build the element tree straight into flint maps and
//! vectors as tokens arrive, rather than materialising the crate's own tree and
//! walking it afterwards.
//!
//! An element is `{:tag :name :attrs {:k "v"} :content [...]}`, matching
//! `clojure.data.xml`'s shape closely enough to guess.

#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;
use xmlparser::{ElementEnd, Token, Tokenizer};

use flint_rt::rt::Rt;
use flint_rt::value::{Value, NIL};

struct Elem {
    tag: usize,      // shadow index: keyword
    attrs: usize,    // shadow index: map
    content: usize,  // shadow index: vector
}

fn qname(prefix: &str, local: &str) -> String {
    if prefix.is_empty() {
        local.into()
    } else {
        let mut s = String::from(prefix);
        s.push('/');
        s.push_str(local);
        s
    }
}

fn finish(rt: &mut Rt, e: &Elem) -> Value {
    let base = rt.mark();
    let m = rt.empty_map();
    let mi = rt.push(m);
    let kt = rt.keyword(None, "tag");
    let v = rt.r(e.tag);
    let nm = rt.map_assoc(rt.r(mi), kt, v);
    rt.set_r(mi, nm);
    let ka = rt.keyword(None, "attrs");
    let v = rt.r(e.attrs);
    let nm = rt.map_assoc(rt.r(mi), ka, v);
    rt.set_r(mi, nm);
    let kc = rt.keyword(None, "content");
    let v = rt.r(e.content);
    let nm = rt.map_assoc(rt.r(mi), kc, v);
    rt.set_r(mi, nm);
    let out = rt.r(mi);
    rt.pop_to(base);
    out
}

fn push_content(rt: &mut Rt, stack: &[Elem], roots: usize, v: Value) {
    match stack.last() {
        Some(e) => {
            let nv = rt.vec_conj(rt.r(e.content), v);
            rt.set_r(e.content, nv);
        }
        None => {
            let nv = rt.vec_conj(rt.r(roots), v);
            rt.set_r(roots, nv);
        }
    }
}

pub fn parse(rt: &mut Rt, src: &str, html_mode: bool) -> Value {
    let base = rt.mark();
    let roots = {
        let v = rt.empty_vec();
        rt.push(v)
    };
    let mut stack: Vec<Elem> = Vec::new();
    let _ = html_mode;

    for tok in Tokenizer::from(src) {
        let tok = match tok {
            Ok(t) => t,
            Err(_) => {
                rt.pop_to(base);
                return rt.throw_str("Exception", "XML parse error");
            }
        };
        match tok {
            Token::ElementStart { prefix, local, .. } => {
                let name = qname(prefix.as_str(), local.as_str());
                let k = rt.keyword(None, &name);
                let tag = rt.push(k);
                let m = rt.empty_map();
                let attrs = rt.push(m);
                let v = rt.empty_vec();
                let content = rt.push(v);
                stack.push(Elem { tag, attrs, content });
            }
            Token::Attribute { prefix, local, value, .. } => {
                if let Some(e) = stack.last() {
                    let name = qname(prefix.as_str(), local.as_str());
                    let k = rt.keyword(None, &name);
                    let ki = rt.push(k);
                    let owned: String = value.as_str().into();
                    let vv = rt.string(&owned);
                    let k = rt.r(ki);
                    let nm = rt.map_assoc(rt.r(e.attrs), k, vv);
                    rt.set_r(e.attrs, nm);
                }
            }
            Token::ElementEnd { end, .. } => match end {
                ElementEnd::Open => {}
                ElementEnd::Empty => {
                    if let Some(e) = stack.pop() {
                        let v = finish(rt, &e);
                        push_content(rt, &stack, roots, v);
                    }
                }
                ElementEnd::Close(..) => {
                    if let Some(e) = stack.pop() {
                        let v = finish(rt, &e);
                        push_content(rt, &stack, roots, v);
                    }
                }
            },
            Token::Text { text } => {
                let owned: String = text.as_str().into();
                if !owned.trim().is_empty() || !stack.is_empty() {
                    let v = rt.string(&owned);
                    push_content(rt, &stack, roots, v);
                }
            }
            Token::Cdata { text, .. } => {
                let owned: String = text.as_str().into();
                let v = rt.string(&owned);
                push_content(rt, &stack, roots, v);
            }
            // Declarations, comments, DOCTYPE and processing instructions are
            // dropped. Named in the README rather than silently half-kept.
            _ => {}
        }
    }

    // Unclosed elements are closed at end of input rather than rejected: real
    // markup does this constantly, and refusing is less useful than recovering.
    while let Some(e) = stack.pop() {
        let v = finish(rt, &e);
        push_content(rt, &stack, roots, v);
    }

    let out = rt.r(roots);
    rt.pop_to(base);
    out
}

pub fn b_xml_parse(rt: &mut Rt, a: usize, n: usize) -> Value {
    let _ = n;
    let v = rt.vat(a);
    let mut buf = flint_rt::rt::sbuf();
    let owned: String = match rt.as_str(v, &mut buf) {
        Some(s) => s.into(),
        None => return rt.throw_str("ClassCastException", "xml/parse-str wants a string"),
    };
    let _ = NIL;
    parse(rt, &owned, false)
}

#[no_mangle]
pub extern "C" fn flint_b_xml_parse(rt: *mut Rt, base: u32, argc: u32) -> u64 {
    unsafe { b_xml_parse(&mut *rt, base as usize, argc as usize).0 }
}
