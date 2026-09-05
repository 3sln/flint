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
        for i in 1..n {
            // Both operands are re-read from the value stack every time round:
            // `=` on a compound value allocates, and the stack is a root the
            // collector updates, so this is the address that stays correct.
            let first = arg(rt, a, 0);
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
    // A map entry answers `vector?` (as Clojure's MapEntry does, being an
    // IPersistentVector) but has its own predicate.
    "vector?", flint_b_vectorp, b_vectorp, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(rt.is_vector(v)
            || (v.is_heap() && crate::obj::ty(&rt.gc.sp, v.as_heap()) == crate::obj::TY_MAPENTRY))
    };
    "flint/map-entry?", flint_b_mapentryp, b_mapentryp, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(v.is_heap() && crate::obj::ty(&rt.gc.sp, v.as_heap()) == crate::obj::TY_MAPENTRY)
    };
    "map?", flint_b_mapp, b_mapp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_map(v)) };
    "set?", flint_b_setp, b_setp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_set(v)) };
    "seq?", flint_b_seqp, b_seqp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_seq(v)) };
    "fn?", flint_b_fnp, b_fnp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_fn(v)) };
    "boolean?", flint_b_boolp, b_boolp, |rt, a, n| { let _ = n; Value::boolean(arg(rt, a, 0).is_bool()) };
    "sequential?", flint_b_sequentialp, b_sequentialp, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); Value::boolean(rt.is_sequential(v)) };

    // --- byte strings (doc/decisions/0024) ----------------------------------
    //
    // The same two tiers as text: flat below `FLAT_MAX`, a shallow B-tree above
    // it. Named `flint/b-*` rather than overloading the string builtins because
    // a byte string carries no UTF-8 semantics -- it cannot be indexed by
    // character and `count` on it is bytes, not code points.
    "bytes?", flint_b_bytesp, b_bytesp, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(rt.is_bytes(v))
    };
    "flint/b-count", flint_b_bcount, b_bcount, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::fixnum(rt.b_count(v) as i64)
    };
    "flint/b-at", flint_b_bat, b_bat, |rt, a, n| {
        let _ = n;
        let (v, i) = (arg(rt, a, 0), arg(rt, a, 1));
        // `NOT_FOUND` as the default, because NIL is a value a caller could
        // legitimately want back and this one needs to tell absent from nil.
        let b = match rt.as_i64(i) {
            Some(i) if i >= 0 => rt.b_at(v, i as u32, crate::value::NOT_FOUND),
            _ => crate::value::NOT_FOUND,
        };
        if b == crate::value::NOT_FOUND {
            rt.throw_str("IndexOutOfBoundsException", "byte index out of range")
        } else {
            b
        }
    };
    "flint/b-concat", flint_b_bconcat, b_bconcat, |rt, a, n| {
        let _ = n;
        let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        rt.b_concat(x, y)
    };
    "flint/b-slice", flint_b_bslice, b_bslice, |rt, a, n| {
        let _ = n;
        let (v, f, t) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2));
        match (rt.as_i64(f), rt.as_i64(t)) {
            (Some(f), Some(t)) if f >= 0 && t >= 0 => rt.b_slice(v, f as u32, t as u32),
            _ => rt.throw_str("IllegalArgumentException", "b-slice wants two integers"),
        }
    };
    // A string's UTF-8 as a byte string. The counterpart of `flint/str-bytes`,
    // which answers with a VECTOR and costs eight bytes per byte.
    "flint/str->b", flint_b_strtob, b_strtob, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        let mut buf = crate::rt::sbuf();
        match rt.as_str(v, &mut buf) {
            Some(s) => {
                let owned: alloc::vec::Vec<u8> = s.as_bytes().to_vec();
                rt.new_bytes(&owned)
            }
            None => rt.throw_str("ClassCastException", "str->b wants a string"),
        }
    };
    "flint/b->str", flint_b_btostr, b_btostr, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if !rt.is_bytes(v) {
            return rt.throw_str("ClassCastException", "b->str wants a byte string");
        }
        let bs = rt.b_to_vec(v);
        match core::str::from_utf8(&bs) {
            Ok(s) => rt.string(s),
            Err(_) => rt.throw_str("IllegalArgumentException", "those bytes are not UTF-8"),
        }
    };
    // From a vector of integers, for building one a byte at a time before the
    // transient exists, and for tests.
    "flint/vec->b", flint_b_vectob, b_vectob, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if !rt.is_vector(v) {
            return rt.throw_str("ClassCastException", "vec->b wants a vector");
        }
        let n = rt.vec_count(v);
        let mut out = alloc::vec::Vec::with_capacity(n as usize);
        for i in 0..n {
            // `i` is bounded by the count above, so the default is
            // unreachable; the failure this arm is about is a non-integer
            // element, which is `as_i64` and not `nth`.
            let x = rt.vec_nth(v, i, crate::value::NIL);
            match rt.as_i64(x) {
                Some(b) => out.push((b & 0xff) as u8),
                None => return rt.throw_str("ClassCastException", "vec->b wants integers"),
            }
        }
        rt.new_bytes(&out)
    };
    "flint/b->vec", flint_b_btovec, b_btovec, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if !rt.is_bytes(v) {
            return rt.throw_str("ClassCastException", "b->vec wants a byte string");
        }
        let bs = rt.b_to_vec(v);
        let base = rt.mark();
        rt.push(rt.roots.shared.singletons[crate::rt::SING_EMPTY_VEC]);
        for b in bs {
            let cur = rt.r(base);
            let next = rt.vec_conj(cur, Value::fixnum(b as i64));
            rt.set_r(base, next);
        }
        let out = rt.r(base);
        rt.pop_to(base);
        out
    };

    // The subtree depth, which is a TEST hook: a byte rope that degenerates
    // into a spine still answers every question correctly and gets slower and
    // then traps, so the shape has to be observable.
    "flint/b-depth", flint_b_bdepth, b_bdepth, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::fixnum(rt.b_depth(v) as i64)
    };

    // A transient byte string. `conj!` takes a BYTE, `append!` takes a whole
    // byte string -- both, because appending a kilobyte one byte at a time is
    // exactly what this type exists to stop doing.
    "flint/b-transient", flint_b_btransient, b_btransient, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if !rt.is_bytes(v) {
            return rt.throw_str("ClassCastException", "b-transient wants a byte string");
        }
        rt.b_transient(v)
    };
    "flint/b-conj!", flint_b_bconjbang, b_bconjbang, |rt, a, n| {
        let _ = n;
        let (t, x) = (arg(rt, a, 0), arg(rt, a, 1));
        match rt.as_i64(x) {
            Some(b) => rt.b_conj(t, (b & 0xff) as u8),
            None => rt.throw_str("ClassCastException", "b-conj! wants an integer"),
        }
    };
    "flint/b-append!", flint_b_bappendbang, b_bappendbang, |rt, a, n| {
        let _ = n;
        let (t, v) = (arg(rt, a, 0), arg(rt, a, 1));
        if !rt.is_bytes(v) {
            return rt.throw_str("ClassCastException", "b-append! wants a byte string");
        }
        rt.b_append_bytes(t, v)
    };
    "flint/b-persistent!", flint_b_bpersistbang, b_bpersistbang, |rt, a, n| {
        let _ = n;
        let t = arg(rt, a, 0);
        rt.b_persistent(t)
    };
    "flint/b-tcount", flint_b_btcount, b_btcount, |rt, a, n| {
        let _ = n;
        let t = arg(rt, a, 0);
        Value::fixnum(rt.b_tcount(t) as i64)
    };

    // --- the type-annotation barrier ---------------------------------------
    //
    // `(let [^int x e] ...)` compiles to a bind of `check-tag(e, INT, where)`.
    // The check is what makes the annotation SOUND rather than a hint: every
    // read of `x` after it is known to be an int, so the reads can be
    // specialised, and the cost is one test at the write instead of one test
    // at each read. An annotation that is already proven emits no call at all
    // -- the analyzer elides it -- so this runs only where something was
    // genuinely unknown.
    //
    // The codes are `flint.types/code`, and `test/types.clj` asserts the two
    // tables agree. They are integers rather than keywords because this is on
    // the write path of every annotated binding.
    "flint/check-tag", flint_b_check_tag, b_check_tag, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        let code = arg(rt, a, 1).as_fixnum();
        // DELEGATES to `type_p` rather than restating it. This match used to
        // be a second copy of that one, and the copies drifted the moment a
        // map entry became a vector -- `vector?` answered from the interpreter
        // said one thing and `check-tag` said another, for the same value.
        //
        // An unknown code is still refused here, because `type_p` answers
        // `sequential?` for anything it does not recognise and an annotation
        // that silently passes everything is worse than one that fails.
        if !(1..=14).contains(&code) {
            return rt.throw_str("IllegalArgumentException",
                                "check-tag: unknown type code");
        }
        let ok = rt.type_p(code as u8, v);
        if ok {
            v
        } else {
            let mut b = crate::rt::sbuf();
            let name = match code {
                1 => "int", 2 => "float", 3 => "number", 4 => "string",
                5 => "keyword", 6 => "symbol", 7 => "boolean", 8 => "vector",
                9 => "map", 10 => "set", 11 => "seq", 12 => "fn", 13 => "nil",
                _ => "sequential",
            };
            let site = arg(rt, a, 2);
            let msg = match rt.as_str(site, &mut b) {
                Some(w) => alloc::format!("{w} is declared ^{name}, and it is not"),
                None => alloc::format!("a value declared ^{name} is not one"),
            };
            rt.throw_str("ClassCastException", &msg)
        }
    };

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
    "pop!", flint_b_popbang, b_popbang, |rt, a, n| {
        let _ = n; let t = arg(rt, a, 0); rt.transient_pop(t)
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

    "flint/lower-case", flint_b_lowercase, b_lowercase, |rt, a, n| {
        let _ = n;
        let s = arg(rt, a, 0);
        rt.change_case(s, false)
    };
    "flint/upper-case", flint_b_uppercase, b_uppercase, |rt, a, n| {
        let _ = n;
        let s = arg(rt, a, 0);
        rt.change_case(s, true)
    };
    "flint/re-compile", flint_b_recompile, b_recompile, |rt, a, n| {
        let _ = n;
        let (src, words) = (arg(rt, a, 0), arg(rt, a, 1));
        rt.re_compile(src, words)
    };
    "flint/re-run", flint_b_rerun, b_rerun, |rt, a, n| {
        let re = arg(rt, a, 0);
        let s = arg(rt, a, 1);
        let from = if n > 2 { rt.as_i64(arg(rt, a, 2)).unwrap_or(0) } else { 0 };
        // 0 searches from `from`; 3 matches exactly at it. Both are entry points
        // into ONE program (`flint.nfa`), so there is no second program to keep
        // in step with the first.
        let entry = if n > 3 { rt.as_i64(arg(rt, a, 3)).unwrap_or(0) as u32 } else { 0 };
        // The fifth argument asks for a match that reaches the END, which is
        // `re-matches` and cannot be had by checking the span afterwards.
        let full = n > 4 && rt.as_i64(arg(rt, a, 4)).unwrap_or(0) != 0;
        rt.re_run(re, s, from, entry, full)
    };
    "flint/re-find-all", flint_b_refindall, b_refindall, |rt, a, n| {
        let re = arg(rt, a, 0);
        let s = arg(rt, a, 1);
        let limit = if n > 2 { rt.as_i64(arg(rt, a, 2)).unwrap_or(0) } else { 0 };
        rt.re_find_all(re, s, limit)
    };

    // The primitive `swap!` retries on (`lib/clojure/core.cljc`). It lives here
    // rather than in core because a read-modify-write cannot be made atomic in
    // the language it is written in.
    //
    // APPENDED rather than inserted, and that is a habit rather than a proven
    // requirement: adding it in the middle of this catalogue, which shifts the
    // table slot of every entry after it, coincided with an intermittent
    // segfault compiling unrelated programs that has not been reproduced since
    // and was never pinned to a cause. Appending costs nothing.
    "compare-and-set!", flint_b_cas, b_cas, |rt, a, n| {
        let _ = n;
        let (at, old, new) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2));
        rt.compare_and_set_atom(at, old, new)
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
    "flint/bytes->str", flint_b_bytesstr, b_bytesstr, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if !rt.is_vector(v) {
            return rt.throw_str("ClassCastException", "bytes->str wants a vector of bytes");
        }
        let count = rt.vec_count(v);
        let mut bytes = alloc::vec::Vec::with_capacity(count as usize);
        for i in 0..count {
            let b = rt.vec_nth(v, i, NIL);
            bytes.push(b.as_fixnum() as u8);
        }
        match core::str::from_utf8(&bytes) {
            Ok(t) => { let owned: alloc::string::String = t.into(); rt.string(&owned) }
            Err(_) => rt.throw_str("IllegalArgumentException", "those bytes are not UTF-8"),
        }
    };
    "flint/bits->double", flint_b_bitsdouble, b_bitsdouble, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        match rt.as_i64(v) {
            Some(b) => Value::from_f64(f64::from_bits(b as u64)),
            None => rt.throw_str("ClassCastException", "bits->double wants an integer"),
        }
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
    "flint/delay", flint_b_delay, b_delay, |rt, a, n| {
        let _ = n;
        let f = arg(rt, a, 0);
        let base = rt.mark();
        let fi = rt.push(f);
        let addr = rt.alloc(crate::obj::TY_DELAY, 2);
        if addr == 0 { rt.pop_to(base); return NIL; }
        let f = rt.r(fi);
        rt.pop_to(base);
        rt.set_slot(addr, 0, f);
        rt.set_slot(addr, 1, NIL);
        Value::heap(addr)
    };
    "flint/realized?", flint_b_realized, b_realized, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if v.is_heap() {
            match crate::obj::ty(&rt.gc.sp, v.as_heap()) {
                crate::obj::TY_DELAY => return Value::boolean(rt.slot(v, 0).is_nil()),
                crate::obj::TY_LAZYSEQ => return Value::boolean(rt.slot(v, 0).is_nil()),
                _ => {}
            }
        }
        TRUE
    };
    "flint/delay?", flint_b_delayp, b_delayp, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(v.is_heap() && crate::obj::ty(&rt.gc.sp, v.as_heap()) == crate::obj::TY_DELAY)
    };
    "flint/unchecked-add", flint_b_uadd, b_uadd, |rt, a, n| {
        let _ = n; unchecked2(rt, a, 0)
    };
    "flint/unchecked-sub", flint_b_usub, b_usub, |rt, a, n| {
        let _ = n; unchecked2(rt, a, 1)
    };
    "flint/unchecked-mul", flint_b_umul, b_umul, |rt, a, n| {
        let _ = n; unchecked2(rt, a, 2)
    };
    "flint/volatile", flint_b_volatile, b_volatile, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        rt.new_volatile(v)
    };
    // Opaque values (doc/decisions/0022): flint's replacement for Clojure's
    // `(Object.)`, which it cannot have because it has no host classes.
    "flint/opaque", flint_b_opaque, b_opaque, |rt, a, n| {
        // The label is for PRINTING and plays no part in identity: two opaque
        // values with the same label are still distinct, and that is the point.
        let label = if n > 0 { arg(rt, a, 0) } else { NIL };
        rt.new_opaque(label, 0)
    };
    "flint/opaque?", flint_b_opaquep, b_opaquep, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(rt.is_opaque(v))
    };
    // The LABEL is readable; the host id is not, and there is deliberately no
    // builtin that returns it. Reading provenance from guest code would invite
    // exactly the check 0022 forbids.
    // `#my.ns/thing v` (`doc/decisions/0034`). The tag must be a SYMBOL, and a
    // namespaced one in practice, because an unqualified tag is reserved for
    // the reader's own literals.
    // --- tables (`doc/decisions/0026`) --------------------------------------
    "flint/schema", flint_b_schema, b_schema, |rt, a, n| {
        let _ = n;
        let pairs = arg(rt, a, 0);
        rt.new_schema(pairs)
    };
    "flint/table", flint_b_table, b_table, |rt, a, n| {
        let _ = n;
        let (s, rows) = (arg(rt, a, 0), arg(rt, a, 1));
        if !rt.is_schema(s) {
            return rt.throw_str(
                "IllegalArgumentException",
                "a table needs a schema; build one with `(schema [[:name :type] ...])`",
            );
        }
        rt.new_table(s, rows)
    };
    "flint/table-migrate", flint_b_tablemig, b_tablemig, |rt, a, n| {
        let _ = n;
        let (t, w, d) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2));
        if !rt.is_table(t) {
            return rt.throw_str("IllegalArgumentException", "migrate wants a table");
        }
        if !rt.is_schema(w) {
            return rt.throw_str(
                "IllegalArgumentException",
                "migrate wants a schema; build one with `(schema [[:name :type] ...])`",
            );
        }
        rt.table_migrate(t, w, d)
    };
    "flint/table-slice", flint_b_tableslice, b_tableslice, |rt, a, n| {
        let _ = n;
        let (t, f, e) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2));
        if !rt.is_table(t) {
            return rt.throw_str("IllegalArgumentException", "slice wants a table");
        }
        let (f, e) = (rt.as_i64(f).unwrap_or(-1), rt.as_i64(e).unwrap_or(-1));
        rt.table_slice(t, f, e)
    };
    "flint/table-column", flint_b_tablecolumn, b_tablecolumn, |rt, a, n| {
        let _ = n;
        let (t, name) = (arg(rt, a, 0), arg(rt, a, 1));
        if !rt.is_table(t) {
            return rt.throw_str("IllegalArgumentException", "column wants a table");
        }
        rt.table_column(t, name)
    };
    "flint/table-reduce-column", flint_b_tablereduce, b_tablereduce, |rt, a, n| {
        let _ = n;
        let (t, name, f, init) = (arg(rt, a, 0), arg(rt, a, 1), arg(rt, a, 2), arg(rt, a, 3));
        if !rt.is_table(t) {
            return rt.throw_str("IllegalArgumentException", "reduce-column wants a table");
        }
        rt.table_reduce_column(t, name, f, init)
    };
    "flint/table?", flint_b_tablep, b_tablep, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(rt.is_table(v))
    };
    "flint/table-schema", flint_b_tableschema, b_tableschema, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if rt.is_table(v) {
            rt.slot(v, crate::table::TB_SCHEMA)
        } else if rt.is_table_ref(v) {
            rt.slot(v, crate::table::RF_SCHEMA)
        } else {
            NIL
        }
    };
    "flint/schema-columns", flint_b_schemacols, b_schemacols, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if rt.is_schema(v) { rt.slot(v, crate::table::SC_NAMES) } else { NIL }
    };
    "flint/schema-types", flint_b_schematypes, b_schematypes, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        if rt.is_schema(v) { rt.slot(v, crate::table::SC_TYPES) } else { NIL }
    };

    "flint/tagged-literal", flint_b_tagged, b_tagged, |rt, a, n| {
        let _ = n;
        let (t, f) = (arg(rt, a, 0), arg(rt, a, 1));
        if !rt.is_symbol(t) {
            return rt.throw_str(
                "IllegalArgumentException",
                "a tagged literal's tag must be a symbol",
            );
        }
        rt.new_tagged(t, f)
    };
    "flint/tagged-literal?", flint_b_taggedp, b_taggedp, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        Value::boolean(rt.is_tagged(v))
    };
    "flint/opaque-label", flint_b_opaquelabel, b_opaquelabel, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        rt.opaque_label(v)
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
    "flint/ex-matches?", flint_b_exmatches, b_exmatches, |rt, a, n| {
        let _ = n;
        let (e, name) = (arg(rt, a, 0), arg(rt, a, 1));
        rt.ex_matches(e, name)
    };

    // --- state (single threaded, and pure enough: no I/O, no coordination) ---
    "atom", flint_b_atom, b_atom, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.new_atom(v) };
    "deref", flint_b_deref, b_deref, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.deref(v) };
    "reset!", flint_b_reset, b_reset, |rt, a, n| {
        let _ = n; let (at, v) = (arg(rt, a, 0), arg(rt, a, 1)); rt.reset_atom(at, v)
    };

    // --- kinds ---------------------------------------------------------------
    // The closed set protocols dispatch on. flint has no types, so "which type
    // is this?" has no general answer -- but the *built-in* kinds are a small
    // fixed list, and everything else dispatches on metadata
    // (doc/decisions/0005, section 6). This lives in the runtime rather than in
    // cljc so that naming `:port` costs nothing: the type tag is here whether or
    // not the concurrency unit is linked.
    "flint/kind", flint_b_kind, b_kind, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        rt.kind_of(v)
    };

    // --- dynamic bindings ----------------------------------------------------
    // Per GREEN thread: the scheduler saves and restores the whole map on a
    // switch, and a spawned thread starts from a snapshot of its spawner's.
    "flint/dyn-get", flint_b_dynget, b_dynget, |rt, a, n| {
        let _ = n;
        let (sym, root) = (arg(rt, a, 0), arg(rt, a, 1));
        let binds = rt.roots.shared.singletons[crate::rt::SING_BINDINGS];
        if binds.is_nil() { return root; }
        rt.map_get(binds, sym, root)
    };
    "flint/dyn-bindings", flint_b_dynbinds, b_dynbinds, |rt, a, n| {
        let _ = (a, n);
        let b = rt.roots.shared.singletons[crate::rt::SING_BINDINGS];
        if b.is_nil() { rt.empty_map() } else { b }
    };
    "flint/dyn-set-bindings", flint_b_dynset, b_dynset, |rt, a, n| {
        let _ = n;
        let m = arg(rt, a, 0);
        rt.roots.shared.singletons[crate::rt::SING_BINDINGS] = m;
        m
    };

    // --- metadata ------------------------------------------------------------
    "meta", flint_b_meta, b_meta, |rt, a, n| { let _ = n; let v = arg(rt, a, 0); rt.meta_of(v) };
    "with-meta", flint_b_withmeta, b_withmeta, |rt, a, n| {
        let _ = n; let (v, m) = (arg(rt, a, 0), arg(rt, a, 1)); rt.with_meta(v, m)
    };

    // --- clojure.math, over libm ---------------------------------------------
    "flint/sqrt", flint_b_m_sqrt, b_m_sqrt, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::sqrt(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/cbrt", flint_b_m_cbrt, b_m_cbrt, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::cbrt(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/exp", flint_b_m_exp, b_m_exp, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::exp(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/expm1", flint_b_m_expm1, b_m_expm1, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::expm1(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/log", flint_b_m_log, b_m_log, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::log(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/log10", flint_b_m_log10, b_m_log10, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::log10(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/log1p", flint_b_m_log1p, b_m_log1p, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::log1p(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/sin", flint_b_m_sin, b_m_sin, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::sin(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/cos", flint_b_m_cos, b_m_cos, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::cos(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/tan", flint_b_m_tan, b_m_tan, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::tan(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/asin", flint_b_m_asin, b_m_asin, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::asin(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/acos", flint_b_m_acos, b_m_acos, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::acos(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/atan", flint_b_m_atan, b_m_atan, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::atan(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/sinh", flint_b_m_sinh, b_m_sinh, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::sinh(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/cosh", flint_b_m_cosh, b_m_cosh, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::cosh(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/tanh", flint_b_m_tanh, b_m_tanh, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::tanh(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/floor", flint_b_m_floor, b_m_floor, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::floor(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/ceil", flint_b_m_ceil, b_m_ceil, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::ceil(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/rint", flint_b_m_rint, b_m_rint, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::rint(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/trunc", flint_b_m_trunc, b_m_trunc, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::trunc(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/pow", flint_b_m_pow, b_m_pow, |rt, a, n| {
        let _ = n; let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        if rt.is_number(x) && rt.is_number(y) {
            Value::from_f64(crate::fmath::pow(rt.num_f64(x), rt.num_f64(y)))
        } else { rt.throw_not_a_number(x, y) }
    };
    "flint/atan2", flint_b_m_atan2, b_m_atan2, |rt, a, n| {
        let _ = n; let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        if rt.is_number(x) && rt.is_number(y) {
            Value::from_f64(crate::fmath::atan2(rt.num_f64(x), rt.num_f64(y)))
        } else { rt.throw_not_a_number(x, y) }
    };
    "flint/hypot", flint_b_m_hypot, b_m_hypot, |rt, a, n| {
        let _ = n; let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        if rt.is_number(x) && rt.is_number(y) {
            Value::from_f64(crate::fmath::hypot(rt.num_f64(x), rt.num_f64(y)))
        } else { rt.throw_not_a_number(x, y) }
    };
    "flint/to-long", flint_b_tolong, b_tolong, |rt, a, n| {
        let _ = n;
        let v = arg(rt, a, 0);
        match rt.as_i64(v) {
            Some(x) => rt.integer(x),
            None if v.is_double() => {
                let d = crate::fmath::trunc(v.as_f64());
                if d.is_finite() && d >= -9.223372036854776e18 && d <= 9.223372036854776e18 {
                    rt.integer(d as i64)
                } else {
                    rt.throw_str("IllegalArgumentException", "value out of long range")
                }
            }
            None => rt.throw_not_a_number(v, v),
        }
    };
    "flint/signum", flint_b_m_signum, b_m_signum, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::signum(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/fabs", flint_b_m_fabs, b_m_fabs, |rt, a, n| {
        let _ = n; let v = arg(rt, a, 0);
        if rt.is_number(v) { Value::from_f64(crate::fmath::abs(rt.num_f64(v))) }
        else { rt.throw_not_a_number(v, v) }
    };
    "flint/copy-sign", flint_b_m_copysign, b_m_copysign, |rt, a, n| {
        let _ = n; let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
        if rt.is_number(x) && rt.is_number(y) {
            let (m, s) = (crate::fmath::abs(rt.num_f64(x)), rt.num_f64(y));
            Value::from_f64(if s.is_sign_negative() { -m } else { m })
        } else { rt.throw_not_a_number(x, y) }
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
            // `apply` is the general way to turn a long sequence into one call,
            // and this walked all of it for the price of ONE builtin dispatch:
            // 2 698 032 steps past an exhausted budget on 300 000 elements,
            // whatever the function was. `(apply + v)` and `(apply str v)` blew
            // it by exactly the same amount, which is what named the SPREAD
            // rather than either callee -- and is why the fix belongs here and
            // not in `str-join`, which was the first suspect and was innocent.
            if !rt.charge_tick(count as u64, 1, "apply") {
                rt.pop_to(base);
                return crate::value::NIL;
            }
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

fn unchecked2(rt: &mut Rt, a: usize, which: u8) -> Value {
    match (rt.as_i64(arg(rt, a, 0)), rt.as_i64(arg(rt, a, 1))) {
        (Some(x), Some(y)) => {
            let r = match which {
                0 => x.wrapping_add(y),
                1 => x.wrapping_sub(y),
                _ => x.wrapping_mul(y),
            };
            rt.integer(r)
        }
        _ => {
            let (x, y) = (arg(rt, a, 0), arg(rt, a, 1));
            if rt.is_number(x) && rt.is_number(y) {
                let (p, q) = (rt.num_f64(x), rt.num_f64(y));
                Value::from_f64(match which { 0 => p + q, 1 => p - q, _ => p * q })
            } else {
                rt.throw_not_a_number(x, y)
            }
        }
    }
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
