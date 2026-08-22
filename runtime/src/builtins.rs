//! The Rust builtins.
//!
//! # The rule that makes modularity work
//!
//! **Nothing in the runtime may call a builtin directly.** Every builtin is
//! reached only through `__indirect_function_table`, and the only thing that
//! puts a function into that table is the element segment `flint` appends
//! *after* linking, from the set of builtins the program actually reached.
//!
//! That is what lets `wasm-ld --gc-sections` delete an unused builtin: with no
//! static registry naming it, and no direct call, nothing references it.
//! A single direct call from anywhere would silently pin it — and, worse, pin
//! whatever it transitively uses. See `doc/decisions/0003-namespace-units.md`.
//!
//! Each builtin is therefore a pair: a plain Rust `fn` with the real body, and a
//! thin `#[no_mangle] extern "C"` wrapper that is the linker-visible symbol.
//! When the wrapper is not exported, both die together.
//!
//! # What belongs here, and what does not
//!
//! Only what cannot be written in the language: allocation, hashing, the number
//! tower, UTF-8, collection internals. Everything composite — `map`, `filter`,
//! `merge`, `clojure.string`, the printer, the readers — is cljc, so it
//! tree-shakes per var. See `doc/decisions/0002-modularity.md`.

use crate::rt::Rt;
use crate::value::{Value, FALSE, NIL, TRUE};

/// Define a builtin. `$export` is the linker-visible symbol; `$inner` holds the
/// body. The wrapper is the only reference to the body, so both are dropped
/// together when the symbol is not exported.
macro_rules! builtin {
    ($export:ident, $inner:ident, |$rt:ident, $a:ident, $n:ident| $body:block) => {
        pub fn $inner($rt: &mut Rt, $a: usize, $n: usize) -> Value $body

        #[no_mangle]
        pub extern "C" fn $export(rt: *mut Rt, base: u32, argc: u32) -> u64 {
            unsafe { $inner(&mut *rt, base as usize, argc as usize).0 }
        }
    };
}

/// Declare the whole set once: names, symbols and bodies together, so the host
/// registry below and the export list the linker is given cannot drift apart.
macro_rules! builtins {
    ($( $name:literal, $export:ident, $inner:ident, |$rt:ident, $a:ident, $n:ident| $body:block );* $(;)?) => {
        $( builtin!($export, $inner, |$rt, $a, $n| $body); )*

        /// The catalogue: `(clojure name, exported symbol)`. Used by the build to
        /// emit the unit manifest, and by the host test harness. On wasm this is
        /// `&'static str` data only — it names no function pointers, so it pins
        /// nothing.
        pub const CATALOGUE: &[(&str, &str)] = &[ $( ($name, stringify!($export)) ),* ];

        /// Host-only: a registry of real function pointers so native builtins
        /// can be exercised by `cargo test`. Deliberately absent on wasm.
        #[cfg(not(target_arch = "wasm32"))]
        pub fn host_registry() -> alloc::vec::Vec<(&'static str, crate::vm::NativeFn)> {
            alloc::vec![ $( ($name, $export as crate::vm::NativeFn) ),* ]
        }
    };
}

#[inline]
fn arg(rt: &Rt, a: usize, i: usize) -> Value {
    rt.vat(a + i)
}

builtins! {
    // --- equality, hashing, identity ---------------------------------------
    "=", flint_b_eq, b_eq, |rt, a, n| {
        if n < 2 { return TRUE; }
        let first = arg(rt, a, 0);
        for i in 1..n {
            let x = arg(rt, a, i);
            if !rt.eq(first, x) { return FALSE; }
        }
        TRUE
    };
    "identical?", flint_b_identical, b_identical, |rt, a, n| {
        let _ = n;
        Value::boolean(arg(rt, a, 0) == arg(rt, a, 1))
    };
    "hash", flint_b_hash, b_hash, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::fixnum(rt.hash_value(v) as i32 as i64)
    };
    "compare", flint_b_compare, b_compare, |rt, a, n| {
        let _ = n;
        let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        Value::fixnum(rt.compare(x, y) as i64)
    };

    // --- arithmetic ---------------------------------------------------------
    "flint/add", flint_b_add, b_add, |rt, a, n| {
        let mut acc = if n == 0 { return Value::fixnum(0) } else { arg(rt, a, 0) };
        for i in 1..n { let x = arg(rt, a, i); acc = rt.num_add(acc, x); if rt.failed() { return NIL } }
        acc
    };
    "flint/sub", flint_b_sub, b_sub, |rt, a, n| {
        if n == 1 { let x = arg(rt, a, 0); return rt.num_neg(x); }
        let mut acc = arg(rt, a, 0);
        for i in 1..n { let x = arg(rt, a, i); acc = rt.num_sub(acc, x); if rt.failed() { return NIL } }
        acc
    };
    "flint/mul", flint_b_mul, b_mul, |rt, a, n| {
        let mut acc = if n == 0 { return Value::fixnum(1) } else { arg(rt, a, 0) };
        for i in 1..n { let x = arg(rt, a, i); acc = rt.num_mul(acc, x); if rt.failed() { return NIL } }
        acc
    };
    "flint/div", flint_b_div, b_div, |rt, a, n| {
        if n == 1 { let x = arg(rt, a, 0); return rt.num_div(Value::fixnum(1), x); }
        let mut acc = arg(rt, a, 0);
        for i in 1..n { let x = arg(rt, a, i); acc = rt.num_div(acc, x); if rt.failed() { return NIL } }
        acc
    };
    "quot", flint_b_quot, b_quot, |rt, a, n| {
        let _ = n; let (x, y) = (arg(rt, a, 0), arg(rt, a, 1)); rt.num_quot(x, y)
    };
    "rem", flint_b_rem, b_rem, |rt, a, n| {
        let _ = n; let (x, y) = (arg(rt, a, 0), arg(rt, a, 1)); rt.num_rem(x, y)
    };
    "flint/lt", flint_b_lt, b_lt, |rt, a, n| { cmp_chain(rt, a, n, -1, false) };
    "flint/le", flint_b_le, b_le, |rt, a, n| { cmp_chain(rt, a, n, -1, true) };
    "flint/gt", flint_b_gt, b_gt, |rt, a, n| { cmp_chain(rt, a, n, 1, false) };
    "flint/ge", flint_b_ge, b_ge, |rt, a, n| { cmp_chain(rt, a, n, 1, true) };
    "flint/num-eq", flint_b_numeq, b_numeq, |rt, a, n| {
        for i in 1..n {
            let (x, y) = (arg(rt, a, i - 1), arg(rt, a, i));
            if !rt.is_number(x) || !rt.is_number(y) { return rt.throw_not_a_number(x, y); }
            if !rt.num_eq(x, y) { return FALSE; }
        }
        TRUE
    };

    // --- bit operations -------------------------------------------------------
    "bit-and", flint_b_bitand, b_bitand, |rt, a, n| { bitop(rt, a, n, 0) };
    "bit-or", flint_b_bitor, b_bitor, |rt, a, n| { bitop(rt, a, n, 1) };
    "bit-xor", flint_b_bitxor, b_bitxor, |rt, a, n| { bitop(rt, a, n, 2) };
    "bit-not", flint_b_bitnot, b_bitnot, |rt, a, n| {
        let _ = n;
        match rt.as_i64(arg(rt, a, 0)) { Some(x) => rt.integer(!x), None => rt.throw_not_a_number(NIL, NIL) }
    };
    "bit-shift-left", flint_b_shl, b_shl, |rt, a, n| { shiftop(rt, a, n, 0) };
    "bit-shift-right", flint_b_shr, b_shr, |rt, a, n| { shiftop(rt, a, n, 1) };
    "unsigned-bit-shift-right", flint_b_ushr, b_ushr, |rt, a, n| { shiftop(rt, a, n, 2) };
    "bit-test", flint_b_bittest, b_bittest, |rt, a, n| {
        let _ = n;
        match (rt.as_i64(arg(rt, a, 0)), rt.as_i64(arg(rt, a, 1))) {
            (Some(x), Some(i)) => Value::boolean((x >> (i & 63)) & 1 == 1),
            _ => rt.throw_not_a_number(NIL, NIL),
        }
    };

    // --- predicates ---------------------------------------------------------
    "nil?", flint_b_nilp, b_nilp, |rt, a, n| { let _ = n; Value::boolean(arg(rt, a, 0).is_nil()) };
    "number?", flint_b_numberp, b_numberp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_number(v)) };
    "int?", flint_b_intp, b_intp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_int(v)) };
    "float?", flint_b_floatp, b_floatp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_float(v)) };
    "string?", flint_b_stringp, b_stringp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_string(v)) };
    "keyword?", flint_b_keywordp, b_keywordp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_keyword(v)) };
    "symbol?", flint_b_symbolp, b_symbolp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_symbol(v)) };
    "vector?", flint_b_vectorp, b_vectorp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_vector(v)) };
    "map?", flint_b_mapp, b_mapp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_map(v)) };
    "set?", flint_b_setp, b_setp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_set(v)) };
    "seq?", flint_b_seqp, b_seqp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_seq(v)) };
    "fn?", flint_b_fnp, b_fnp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_fn(v)) };
    "boolean?", flint_b_boolp, b_boolp, |rt, a, n| { let _ = n; Value::boolean(arg(rt, a, 0).is_bool()) };
    "sequential?", flint_b_sequentialp, b_sequentialp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_sequential(v)) };

    // --- collections --------------------------------------------------------
    "count", flint_b_count, b_count, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::fixnum(rt.count_of(v) as i64)
    };
    "first", flint_b_first, b_first, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.first(v) };
    "rest", flint_b_rest, b_rest, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.rest(v) };
    "next", flint_b_next, b_next, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.next(v) };
    "seq", flint_b_seq, b_seq, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.seq(v) };
    "cons", flint_b_cons, b_cons, |rt, a, n| { let _ = n; let (x, s) = (arg(rt, a, 0), arg(rt, a, 1)); rt.cons(x, s) };
    "conj", flint_b_conj, b_conj, |rt, a, n| {
        let mut acc = arg(rt, a, 0);
        let ai = rt.push(acc);
        for i in 1..n { let x = arg(rt, a, i); let v = rt.conj(rt.r(ai), x); rt.set_r(ai, v); }
        acc = rt.r(ai); rt.pop_to(ai); acc
    };
    "get", flint_b_get, b_get, |rt, a, n| {
        let (c, k) = (arg(rt, a, 0), arg(rt, a, 1));
        let d = if n > 2 { arg(rt, a, 2) } else { NIL };
        rt.get(c, k, d)
    };
    "assoc", flint_b_assoc, b_assoc, |rt, a, n| {
        let mut acc = arg(rt, a, 0);
        let ai = rt.push(acc);
        let mut i = 1;
        while i + 1 < n + 1 && i + 1 <= n {
            let (k, v) = (arg(rt, a, i), arg(rt, a, i + 1));
            let nv = rt.assoc(rt.r(ai), k, v);
            rt.set_r(ai, nv);
            i += 2;
        }
        acc = rt.r(ai); rt.pop_to(ai); acc
    };
    "dissoc", flint_b_dissoc, b_dissoc, |rt, a, n| {
        let mut acc = arg(rt, a, 0);
        let ai = rt.push(acc);
        for i in 1..n {
            let k = arg(rt, a, i);
            let m = rt.r(ai);
            if rt.is_map(m) { let nv = rt.map_dissoc(m, k); rt.set_r(ai, nv); }
        }
        acc = rt.r(ai); rt.pop_to(ai); acc
    };
    "disj", flint_b_disj, b_disj, |rt, a, n| {
        let mut acc = arg(rt, a, 0);
        let ai = rt.push(acc);
        for i in 1..n {
            let k = arg(rt, a, i);
            let s = rt.r(ai);
            if rt.is_set(s) { let nv = rt.set_disj(s, k); rt.set_r(ai, nv); }
        }
        acc = rt.r(ai); rt.pop_to(ai); acc
    };
    "contains?", flint_b_containsp, b_containsp, |rt, a, n| {
        let _ = n;
        let (c, k) = (arg(rt, a, 0), arg(rt, a, 1));
        Value::boolean(rt.contains(c, k))
    };
    "nth", flint_b_nth, b_nth, |rt, a, n| {
        let (c, i) = (arg(rt, a, 0), arg(rt, a, 1));
        let d = if n > 2 { Some(arg(rt, a, 2)) } else { None };
        rt.nth(c, i, d)
    };
    "pop", flint_b_pop, b_pop, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.pop_of(v) };
    "peek", flint_b_peek, b_peek, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.peek_of(v) };
    "empty", flint_b_empty, b_empty, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.empty_of(v) };

    // --- transients ---------------------------------------------------------
    "transient", flint_b_transient, b_transient, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0); rt.to_transient(v)
    };
    "persistent!", flint_b_persistent, b_persistent, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0); rt.to_persistent(v)
    };
    "conj!", flint_b_conjbang, b_conjbang, |rt, a, n| {
        let _ = n; let (t, x) = (arg(rt, a, 0), arg(rt, a, 1)); rt.transient_conj(t, x)
    };
    "assoc!", flint_b_assocbang, b_assocbang, |rt, a, n| {
        let _ = n;
        let (t, k, v) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2));
        rt.transient_assoc(t, k, v)
    };
    "dissoc!", flint_b_dissocbang, b_dissocbang, |rt, a, n| {
        let _ = n; let (t, k) = (arg(rt, a, 0), arg(rt, a, 1)); rt.transient_dissoc(t, k)
    };

    // --- strings, symbols, keywords -----------------------------------------
    "flint/str2", flint_b_str2, b_str2, |rt, a, n| {
        let _ = n;
        let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        rt.str_concat2(x, y)
    };
    "name", flint_b_name, b_name, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.name_of(v) };
    "namespace", flint_b_namespace, b_namespace, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.ns_of(v) };
    "flint/keyword2", flint_b_keyword2, b_keyword2, |rt, a, n| {
        let (ns, nm) = if n == 1 { (NIL, arg(rt, a, 0)) } else { (arg(rt, a, 0), arg(rt, a, 1)) };
        rt.keyword_from_values(ns, nm)
    };
    "flint/symbol2", flint_b_symbol2, b_symbol2, |rt, a, n| {
        let (ns, nm) = if n == 1 { (NIL, arg(rt, a, 0)) } else { (arg(rt, a, 0), arg(rt, a, 1)) };
        rt.symbol_from_values(ns, nm)
    };
    "flint/subs", flint_b_subs, b_subs, |rt, a, n| {
        let s = arg(rt, a, 0);
        let start = rt.as_i64(arg(rt, a, 1)).unwrap_or(0);
        let end = if n > 2 { rt.as_i64(arg(rt, a, 2)) } else { None };
        rt.substring(s, start, end)
    };
    "flint/num->str", flint_b_num2str, b_num2str, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0); rt.number_to_string(v)
    };
    "flint/str->num", flint_b_str2num, b_str2num, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0); rt.string_to_number(v)
    };
    "flint/code-point-at", flint_b_cpat, b_cpat, |rt, a, n| {
        let _ = n;
        let (s, i) = (arg(rt, a, 0), arg(rt, a, 1));
        rt.code_point_at(s, i)
    };
    "flint/from-code-point", flint_b_fromcp, b_fromcp, |rt, a, n| {
        let _ = n;
        let c = arg(rt, a, 0);
        match rt.as_i64(c).and_then(|c| u32::try_from(c).ok()).and_then(char::from_u32) {
            Some(ch) => Value::char_value(ch),
            None => rt.throw_str("IllegalArgumentException", "not a code point"),
        }
    };

    "flint/str-join", flint_b_strjoin, b_strjoin, |rt, a, n| {
        let _ = n;
        let coll = arg(rt, a, 0);
        rt.join_strings(coll)
    };
    "flint/str-index-of", flint_b_strindexof, b_strindexof, |rt, a, n| {
        let (h, needle) = (arg(rt, a, 0), arg(rt, a, 1));
        let from = if n > 2 { rt.as_i64(arg(rt, a, 2)).unwrap_or(0) } else { 0 };
        rt.str_index_of(h, needle, from)
    };
    "flint/str-bytes", flint_b_strbytes, b_strbytes, |rt, a, n| {
        let _ = n;
        let s = arg(rt, a, 0);
        rt.string_bytes_vector(s)
    };
    "flint/double-bits", flint_b_doublebits, b_doublebits, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if v.is_double() { rt.integer(v.as_f64().to_bits() as i64) }
        else { rt.throw_str("ClassCastException", "not a double") }
    };
    // Build an insertion-ordered array-map of any size, without promoting to a
    // hash map. The reader uses this for map literals so that source order
    // survives -- the compiler's own map literals have side effects in their
    // values, and two hosts iterating them differently is enough to break the
    // self-hosting fixpoint.
    "flint/array-map", flint_b_arraymap, b_arraymap, |rt, a, n| {
        let _ = n;
        let kvs = arg(rt, a, 0);
        rt.ordered_map(kvs)
    };
    "flint/volatile", flint_b_volatile, b_volatile, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        rt.new_volatile(v)
    };
    "flint/volatile?", flint_b_volatilep, b_volatilep, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(v.is_heap() && crate::obj::ty(&rt.gc.sp, v.as_heap()) == crate::obj::TY_VOLATILE)
    };

    // --- errors --------------------------------------------------------------
    "ex-info", flint_b_exinfo, b_exinfo, |rt, a, n| {
        let msg = arg(rt, a, 0);
        let data = if n > 1 { arg(rt, a, 1) } else { NIL };
        let cause = if n > 2 { arg(rt, a, 2) } else { NIL };
        let mi = rt.push(msg);
        let di = rt.push(data);
        let ci = rt.push(cause);
        let kind = rt.string("ExceptionInfo");
        let (m, d, c) = (rt.r(mi), rt.r(di), rt.r(ci));
        rt.pop_to(mi);
        rt.ex_info(kind, m, d, c)
    };
    "ex-message", flint_b_exmessage, b_exmessage, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.ex_message(v) };
    "ex-data", flint_b_exdata, b_exdata, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.ex_data(v) };
    "flint/ex-kind", flint_b_exkind, b_exkind, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.ex_kind(v) };

    // --- state (single threaded, and pure enough: no I/O, no coordination) ---
    "atom", flint_b_atom, b_atom, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.new_atom(v) };
    "deref", flint_b_deref, b_deref, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.deref(v) };
    "reset!", flint_b_reset, b_reset, |rt, a, n| {
        let _ = n; let (at, v) = (arg(rt, a, 0), arg(rt, a, 1)); rt.reset_atom(at, v)
    };

    // --- metadata ------------------------------------------------------------
    "meta", flint_b_meta, b_meta, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.meta_of(v) };
    "with-meta", flint_b_withmeta, b_withmeta, |rt, a, n| {
        let _ = n; let (v, m) = (arg(rt, a, 0), arg(rt, a, 1)); rt.with_meta(v, m)
    };

    // --- calling back into the interpreter ------------------------------------
    "flint/apply", flint_b_apply, b_apply, |rt, a, n| {
        let _ = n;
        let (f, args) = (arg(rt, a, 0), arg(rt, a, 1));
        let base = rt.mark();
        let fi = rt.push(f);
        let si = rt.push(args);
        let mut cur = rt.seq(rt.r(si));
        rt.set_r(si, cur);
        let mut count = 0usize;
        while !rt.r(si).is_nil() {
            let x = rt.first(rt.r(si));
            rt.push(x);
            count += 1;
            cur = rt.next(rt.r(si));
            rt.set_r(si, cur);
        }
        let argv: alloc::vec::Vec<Value> = (0..count).map(|i| rt.r(si + 1 + i)).collect();
        let f = rt.r(fi);
        let out = rt.invoke(f, &argv);
        rt.pop_to(base);
        out
    };

    // --- lazy sequences -------------------------------------------------------
    "flint/lazy-seq", flint_b_lazyseq, b_lazyseq, |rt, a, n| {
        let _ = n; let f = arg(rt, a, 0); rt.lazy_seq(f)
    };
    "flint/range3", flint_b_range3, b_range3, |rt, a, n| {
        let _ = n;
        let (s, e, st) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2));
        rt.range(s, e, st)
    };

    // --- gc / diagnostics -----------------------------------------------------
    "flint/gc-stats", flint_b_gcstats, b_gcstats, |rt, a, n| {
        let _ = (a, n);
        rt.gc_stats_map()
    };
}

fn bitop(rt: &mut Rt, a: usize, n: usize, which: u8) -> Value {
    let mut acc = match rt.as_i64(arg(rt, a, 0)) {
        Some(x) => x,
        None => return rt.throw_not_a_number(NIL, NIL),
    };
    for i in 1..n {
        match rt.as_i64(arg(rt, a, i)) {
            Some(x) => acc = match which { 0 => acc & x, 1 => acc | x, _ => acc ^ x },
            None => return rt.throw_not_a_number(NIL, NIL),
        }
    }
    rt.integer(acc)
}

fn shiftop(rt: &mut Rt, a: usize, n: usize, which: u8) -> Value {
    let _ = n;
    match (rt.as_i64(arg(rt, a, 0)), rt.as_i64(arg(rt, a, 1))) {
        (Some(x), Some(k)) => {
            let k = (k & 63) as u32;
            let r = match which {
                0 => x.wrapping_shl(k),
                1 => x.wrapping_shr(k),
                _ => ((x as u64) >> k) as i64,
            };
            rt.integer(r)
        }
        _ => rt.throw_not_a_number(NIL, NIL),
    }
}

fn cmp_chain(rt: &mut Rt, a: usize, n: usize, want: i32, or_eq: bool) -> Value {
    for i in 1..n {
        let (x, y) = (arg(rt, a, i - 1), arg(rt, a, i));
        if !rt.is_number(x) || !rt.is_number(y) {
            return rt.throw_not_a_number(x, y);
        }
        let c = rt.num_cmp(x, y);
        let ok = if c == 0 { or_eq } else { c == want };
        if !ok {
            return FALSE;
        }
    }
    TRUE
}
