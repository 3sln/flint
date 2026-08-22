//! `flint.data.html` — a documented subset, adapted from `htmlparser`.
//!
//! # What this is not
//!
//! It is **not** a spec-complete HTML5 parser. HTML5 parsing is a state machine
//! with implied tags, foster parenting, adoption-agency error recovery and a
//! table of elements that close each other; that is weeks of work and the brief
//! says so. `htmlparser` gives us a tolerant *tokenizer* — unquoted attribute
//! values, bare `&`, mixed case, stray `<` — and this file adds the two tree
//! rules that matter most in practice:
//!
//! * **void elements** (`br`, `img`, `meta`, ...) never take children;
//! * **an end tag closes the nearest matching open element**, popping anything
//!   left open in between, so `<p>a<p>b` and `<ul><li>x<li>y` come out sensibly.
//!
//! What it does NOT do is in the README: no implied `<html>`/`<body>`, no
//! `<table>` fostering, no `<script>`/`<style>` raw-text mode, no character
//! entity decoding beyond what the tokenizer does, and no adoption agency.

#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;
use htmlparser::{ElementEnd, Token, Tokenizer};

use flint_rt::rt::Rt;
use flint_rt::value::Value;

/// Elements that never have children, per the HTML spec's void element list.
const VOID: &[&str] = &[
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
    "source", "track", "wbr",
];

/// Implied end tags: starting the first element closes an open one of any kind
/// in the second list. This is a deliberate SUBSET of HTML5's rules -- the ones
/// that appear in essentially all real markup -- and the README lists it so
/// nobody has to read this table to find out what is covered.
const IMPLIED_CLOSE: &[(&str, &[&str])] = &[
    ("p", &["p"]),
    ("li", &["li"]),
    ("dt", &["dt", "dd"]),
    ("dd", &["dt", "dd"]),
    ("tr", &["tr", "td", "th"]),
    ("td", &["td", "th"]),
    ("th", &["td", "th"]),
    ("thead", &["thead", "tbody", "tfoot"]),
    ("tbody", &["thead", "tbody", "tfoot"]),
    ("tfoot", &["thead", "tbody", "tfoot"]),
    ("option", &["option"]),
    ("optgroup", &["option", "optgroup"]),
];

/// Block-level elements that also close an open `<p>`, as HTML5 requires.
const CLOSES_P: &[&str] = &[
    "address", "article", "aside", "blockquote", "div", "dl", "fieldset", "figcaption",
    "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main",
    "nav", "ol", "p", "pre", "section", "table", "ul",
];

struct Elem {
    name: String,
    tag: usize,
    attrs: usize,
    content: usize,
}

fn finish(rt: &mut Rt, e: &Elem) -> Value {
    let base = rt.mark();
    let m = rt.empty_map();
    let mi = rt.push(m);
    for (kname, slot) in [("tag", e.tag), ("attrs", e.attrs), ("content", e.content)] {
        let k = rt.keyword(None, kname);
        let ki = rt.push(k);
        let v = rt.r(slot);
        let k = rt.r(ki);
        let nm = rt.map_assoc(rt.r(mi), k, v);
        rt.set_r(mi, nm);
        rt.pop_to(ki);
    }
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

fn close_top(rt: &mut Rt, stack: &mut Vec<Elem>, roots: usize) {
    if let Some(e) = stack.pop() {
        let v = finish(rt, &e);
        push_content(rt, stack, roots, v);
    }
}

fn lower(s: &str) -> String {
    s.chars().map(|c| c.to_ascii_lowercase()).collect()
}

pub fn parse(rt: &mut Rt, src: &str) -> Value {
    let base = rt.mark();
    let roots = {
        let v = rt.empty_vec();
        rt.push(v)
    };
    let mut stack: Vec<Elem> = Vec::new();

    for tok in Tokenizer::from(src) {
        let tok = match tok {
            Ok(t) => t,
            // Tolerant by design: a token we cannot read is skipped rather than
            // failing the whole document. Real markup is full of these.
            Err(_) => continue,
        };
        match tok {
            Token::ElementStart { local, .. } => {
                let name = lower(local.as_str());
                // Implied end tags, so <p>a<p>b and <li>x<li>y are siblings.
                if let Some((_, closes)) = IMPLIED_CLOSE.iter().find(|(t, _)| *t == name) {
                    while stack.last().map(|e| closes.contains(&e.name.as_str())).unwrap_or(false) {
                        close_top(rt, &mut stack, roots);
                    }
                }
                if CLOSES_P.contains(&name.as_str()) {
                    while stack.last().map(|e| e.name == "p").unwrap_or(false) {
                        close_top(rt, &mut stack, roots);
                    }
                }
                let k = rt.keyword(None, &name);
                let tag = rt.push(k);
                let m = rt.empty_map();
                let attrs = rt.push(m);
                let v = rt.empty_vec();
                let content = rt.push(v);
                stack.push(Elem { name, tag, attrs, content });
            }
            Token::Attribute { local, value, .. } => {
                if let Some(e) = stack.last() {
                    let name = lower(local.as_str());
                    let k = rt.keyword(None, &name);
                    let ki = rt.push(k);
                    let owned: String = value.map(|v| v.as_str().into()).unwrap_or(name);
                    let vv = rt.string(&owned);
                    let k = rt.r(ki);
                    let nm = rt.map_assoc(rt.r(e.attrs), k, vv);
                    rt.set_r(e.attrs, nm);
                }
            }
            Token::ElementEnd { end, .. } => match end {
                ElementEnd::Open => {
                    let is_void = stack.last().map(|e| VOID.contains(&e.name.as_str())).unwrap_or(false);
                    if is_void {
                        close_top(rt, &mut stack, roots);
                    }
                }
                ElementEnd::Empty => close_top(rt, &mut stack, roots),
                ElementEnd::Close(_, local) => {
                    // Close the nearest matching element, discarding anything
                    // left open inside it. This is the single rule that makes
                    // <p>a<p>b and <li>x<li>y come out right.
                    let want = lower(local.as_str());
                    if let Some(depth) = stack.iter().rposition(|e| e.name == want) {
                        while stack.len() > depth {
                            close_top(rt, &mut stack, roots);
                        }
                    }
                    // An end tag with no matching start is ignored.
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
            _ => {}
        }
    }

    while !stack.is_empty() {
        close_top(rt, &mut stack, roots);
    }

    let out = rt.r(roots);
    rt.pop_to(base);
    out
}

pub fn b_html_parse(rt: &mut Rt, a: usize, n: usize) -> Value {
    let _ = n;
    let v = rt.vat(a);
    let mut buf = flint_rt::rt::sbuf();
    let owned: String = match rt.as_str(v, &mut buf) {
        Some(s) => s.into(),
        None => return rt.throw_str("ClassCastException", "html/parse wants a string"),
    };
    parse(rt, &owned)
}

#[no_mangle]
pub extern "C" fn flint_b_html_parse(rt: *mut Rt, base: u32, argc: u32) -> u64 {
    unsafe { b_html_parse(&mut *rt, base as usize, argc as usize).0 }
}
