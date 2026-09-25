//! Generic collection operations: the polymorphic dispatch that `conj`, `get`,
//! `assoc`, `nth`, `count` and friends need, plus transients for maps and sets,
//! atoms, metadata and number formatting.

use crate::hash;
use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, INLINE_MAX, NIL};


impl Rt {
    // --- count -------------------------------------------------------------

    // `count_of` is GENERATED, from `kin/collgen.kin`.


    /// True when a code-point index into `s` is also a byte index.
    #[inline]
    /// DELEGATES to `s_ascii`, which answers the same question for all three
    /// tiers -- but KEEPS THE GUARD, which is the one thing this had and that
    /// does not: `s_ascii` reads the header bit of whatever it is given, so a
    /// heap value that is not a string would be read as one.
    fn str_indexable(&self, s: Value) -> bool {
        if !self.is_string(s) {
            return false;
        }
        self.s_ascii(s)
    }

    // --- conj / assoc / get -------------------------------------------------

    /// GENERATED (`kin/collconj.kin`). The ARMS were already generated --
    /// `vec_conj`, `set_conj`, `map_assoc`, `map_conj_map`, `table_conj` and
    /// `map_entry_as_vec` each have three targets -- and what stayed
    /// hand-written three times was the choice between them. The two shapes
    /// had drifted: this side re-read the type tag once per argument, both
    /// ports read it once and then looped. Not billed either way
    /// (`runtimes/conform/conjgas.cljc` measures it), but two algorithms for
    /// one operation is what `map_conj_map` was generated to stop.
    pub fn conj(&mut self, coll: Value, x: Value) -> Value {
        self.coll_conj(coll, x)
    }

    /// `first_foreign_key` walks a map's entries and needs the key half of
    /// one; this is the same two-shapes-of-entry rule the rest of `conj` uses.
    pub(crate) fn slot_or_nth_pub(&mut self, v: Value, i: u32) -> Value {
        self.slot_or_nth(v, i)
    }

    fn slot_or_nth(&mut self, v: Value, i: u32) -> Value {
        if ty(&self.gc.sp, v.as_heap()) == TY_MAPENTRY {
            self.slot(v, i)
        } else {
            self.vec_nth(v, i, NIL)
        }
    }

    // `assoc` is GENERATED, from `kin/collwrite.kin`, as `coll_assoc_gen`.

    // `get` and `contains` are GENERATED, from `kin/collread.kin`, under the
    // names `coll_get` and `coll_contains`.

    // `nth` is GENERATED too, under the name `coll_nth`. It takes NOT_FOUND
    // for its default where this took `Option<Value>`: the sentinel is the
    // shape all three runtimes can say.

    // `pop_of`, `peek_of` and `empty_of` are GENERATED, from
    // `kin/collgen.kin`.

    // `to_transient`, `not_a_transient`, `to_persistent`, `transient_conj`,
    // `transient_assoc`, `transient_dissoc` and `transient_pop` are
    // GENERATED, from `kin/transients.kin`.

    // --- atoms ---------------------------------------------------------------

    // --- metadata -------------------------------------------------------------

    // --- strings ---------------------------------------------------------------

    pub fn str_concat2(&mut self, x: Value, y: Value) -> Value {
        if !self.is_string(x) || !self.is_string(y) {
            return self.throw_str("ClassCastException", "not a string");
        }
        // A tree join, not a copy (`DECISIONS.md#strings-and-matching`). Small results still
        // copy into a flat string -- `s_concat` decides -- because below the
        // threshold the metadata costs more than the copy it saves.
        //
        // Gas is charged for the bytes only when they are actually moved, which
        // is what makes repeated concatenation linear in gas as well as in time.
        self.s_concat(x, y)
    }

    /// The one-character string at code-point index `i`.
    /// The byte offset of code point `i`, and the bytes there.
    ///
    /// ONE path for both tiers, and NO STATE. A rope descends its own
    /// code-point counts; a flat string is either ASCII -- where the index is
    /// the offset -- or short enough that `raw_string` left it flat, which is
    /// what `INDEX_LEAF` decides.
    ///
    /// This replaced a one-entry cursor, and the reason is worth keeping: a
    /// cursor made the cost of an index depend on what was indexed BEFORE it,
    /// and the cursor had to be invalidated by the collector. So the same walk
    /// cost 8 000 gas undisturbed and 8 500 with one collection halfway through
    /// -- and under parallel executors that collection belongs to another
    /// thread. `DECISIONS.md#resource-limits` says gas is deterministic; a memo keyed on
    /// collector state is not. `runtime/tests/determinism.rs` is the guard.
    fn cp_bytes_at(&mut self, s: Value, i: u32, out: &mut [u8; 4]) -> Option<u32> {
        // WHICH MECHANISM, AND WHY -- `strings-and-matching` calls them complementary and this
        // is the line where that has to be acted on.
        //
        // EVERY ROPE DESCENDS, ASCII included, and this comment used to say the
        // opposite -- that ASCII flattens once and indexes by byte, with a
        // 3.05x measurement behind it. That was true of an older shape and the
        // code below stopped doing it; the comment did not, and BOTH PORTS were
        // written from the comment. So `(nth s i)` on a large ASCII rope
        // materialised the tree on the JVM and the CLR and not here, which no
        // conform diff could see because both answers are right.
        //
        // For ASCII a code-point index IS a byte index, so the descent is this
        // same walk with the lookup skipped -- and flattening would turn an
        // O(log n) descent into an O(n) copy that also caches the flat form,
        // undoing the tree for every later read (`DECISIONS.md#strings-and-matching`).
        if self.is_rope(s) {
            // ASCII included: for ASCII the code-point index IS the byte index,
            // so the descent is the same walk with the lookup skipped. It used
            // to flatten here, which turns an O(log n) descent into an O(n)
            // copy AND caches the flat form, undoing the tree for every later
            // read (`DECISIONS.md#strings-and-matching`).
            // PAST THE END NEEDS NO CHECK HERE. `rope_byte_of_cp` answers the
            // byte length when there is no such code point, and
            // `rope_bytes_at` answers 0 for an offset at or past the end -- so
            // the one test below catches both, where an `Option` needed two.
            let byte = if self.s_ascii(s) { i } else { self.rope_byte_of_cp(s, i) };
            let sk = self.sink_open();
            let w = self.rope_bytes_at(s, byte, sk);
            out[..w as usize].copy_from_slice(&self.sink_array(sk)[..w as usize]);
            self.sink_close(sk);
            return if w == 0 { None } else { Some(w) };
        }
        let s = self.string_arg(s);
        let mut buf = crate::rt::sbuf();
        let b: &[u8] = if s.is_inline_str() {
            s.inline_bytes(&mut buf)
        } else {
            str_bytes(&self.gc.sp, s.as_heap())
        };
        let mut scanned = 0u32;
        let at = if self.str_indexable(s) {
            i
        } else {
            // A flat non-ASCII run, bounded by `INDEX_LEAF`: anything longer
            // arrives as a tree, so this scan is O(INDEX_LEAF), not O(n).
            let mut at = 0u32;
            for _ in 0..i {
                if at as usize >= b.len() {
                    return None;
                }
                at += self.utf8_width(b[at as usize] as u32);
            }
            scanned = at;
            at
        };
        if at as usize >= b.len() {
            return None;
        }
        let w = self.utf8_width(b[at as usize] as u32);
        out[..w as usize].copy_from_slice(&b[at as usize..at as usize + w as usize]);
        // After the borrow of `b` ends: charged for what was walked.
        self.charge_bytes(scanned);
        Some(w)
    }

    /// The code point at `i`, as a one-character string.
    ///
    /// NOTE THE MISSING `string_arg`. Every indexing path used to flatten a
    /// rope first, which threw away the per-node code-point counts
    /// `DECISIONS.md#strings-and-matching` computes, stores and traces for exactly this
    /// question -- and then answered it by scanning.
    /// The one-character string at code point `i`, or `dflt` past the end.
    ///
    /// A DEFAULT rather than an `Option`, the same convergence `vec_nth` and
    /// `map_get` already made: the ports answered a sentinel, Rust answered
    /// `Option`, and every caller here was spelling a default out anyway.
    // @kin:link:ns: flint.rt.strs
    // @kin:link:form:char-at: {:template "{0}.char_at({1}, {2}, {3})"}
    pub fn char_at(&mut self, s: Value, i: u32, dflt: Value) -> Value {
        let mut out = [0u8; 4];
        match self.cp_bytes_at(s, i, &mut out) {
            Some(w) => Value::inline_str(&out[..w as usize]),
            None => dflt,
        }
    }

    pub fn code_point_at(&mut self, s: Value, i: Value) -> Value {
        // BOUNDED BEFORE THE NARROWING. `cp_bytes_at` takes a `u32`, and
        // `idx as u32` below truncates -- so `(code-point-at s 281474976810657)`
        // narrowed to 1 and answered the code point at index 1 rather than
        // refusing. A guard that only checks the SIGN is not a bound.
        let idx = match self.as_i64(i) {
            Some(n) if (0..=u32::MAX as i64).contains(&n) => n as usize,
            _ => return self.throw_str("IndexOutOfBoundsException", "bad index"),
        };
        let mut out = [0u8; 4];
        let w = match self.cp_bytes_at(s, idx as u32, &mut out) {
            Some(w) => w as usize,
            None => return self.throw_str("IndexOutOfBoundsException", "string index out of range"),
        };
        match core::str::from_utf8(&out[..w]).ok().and_then(|t| t.chars().next()) {
            Some(c) => Value::fixnum(c as u32 as i64),
            None => self.throw_str("IndexOutOfBoundsException", "string index out of range"),
        }
    }

    /// `subs` over a tree, by descent: two offsets and a byte copy.
    ///
    /// `charge_bytes` is for the SLICE, not the source, for the reason the flat
    /// path already records -- charging the whole string per call makes the
    /// counter quadratic for splitting even when the code is not.
    fn rope_substring(&mut self, s: Value, start: i64, end: Option<i64>) -> Value {
        let n = self.s_count(s) as i64;
        let e = end.unwrap_or(n);
        if start < 0 || e > n || start > e {
            // THE RANGE IS NAMED; see the identical note on the rope path.
            let msg = alloc::format!("subs {start}..{e} of {n}");
            return self.throw_str("StringIndexOutOfBoundsException", &msg);
        }
        if !self.charge_checked(((e - start) as u64 / 8) + 1, "subs") {
            return crate::value::NIL;
        }
        if start == e {
            return self.string("");
        }
        // `cps` CAPTURED BEFORE `n` IS SHADOWED. The refusal below names the
        // same range as the one above, and the ports' equivalent prints the
        // CODE POINT count -- which the rebinding to `s_bytes` on the next line
        // puts out of reach.
        let cps = n;
        let n = self.s_bytes(s);
        let from = self.rope_byte_of_cp(s, start as u32);
        if from >= n {
            let msg = alloc::format!("subs {start}..{e} of {cps}");
            return self.throw_str("StringIndexOutOfBoundsException", &msg);
        }
        // The END offset is the start of code point `e`, or the whole byte
        // length when `e` is the count -- there is no code point AT the end.
        // Both branches now answer `n` for "past the end", so the second one
        // has nothing left to decide.
        let to = if e as u32 == self.s_count(s) { n } else { self.rope_byte_of_cp(s, e as u32) };
        self.rope_slice(s, from, to)
    }

    /// `subs`, in code points.
    pub fn substring(&mut self, s: Value, start: i64, end: Option<i64>) -> Value {
        // NO `string_arg` on the non-ASCII path. Flattening first turned a tree
        // into one long run and then walked it with `chars().skip(start)`,
        // which is O(start) per call -- so slicing a string n times was
        // quadratic, which is the defect `DECISIONS.md#strings-and-matching` predicts in the
        // sentence "without the flag, `nth` and `subs` on a flat string are
        // O(n) and splitting one is quadratic". The tree answers both offsets
        // by descent instead.
        if self.is_rope(s) && !self.s_ascii(s) {
            return self.rope_substring(s, start, end);
        }
        // AN ASCII ROPE DESCENDS TOO. This used to fall through to
        // `string_arg`, which flattens -- so the non-ASCII path was careful and
        // the easy path was not, which is the wrong way round and exactly the
        // "flatten because the platform likes flat things" that `strings-and-matching` exists
        // to refuse. For ASCII a code point IS a byte, so `append_range` is the
        // whole implementation and it never materialises the tree.
        if self.is_rope(s) {
            let n = self.s_count(s) as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                // THE RANGE IS NAMED. This said only "bad substring range" while
                // both ports said `subs 5..1 of 3`, so the three runtimes refused
                // the same call with different text. The ports' form is the more
                // useful of the two and native's exception CLASS is the more
                // precise, so each side took the other's better half.
                let msg = alloc::format!("subs {start}..{e} of {n}");
                return self.throw_str("StringIndexOutOfBoundsException", &msg);
            }
            return self.rope_slice(s, start as u32, e as u32);
        }
        let s = self.string_arg(s);
        // The slice, not the source. Charging the whole string per call made
        // the COUNTER quadratic for splitting -- n slices of an n-byte string is
        // n^2 gas for n bytes of copying -- which is the same defect as
        // `str_index_of`'s charge, in the same shape, one function along.
        // `test/scaling.clj` found it on its first run.
        let n = if self.is_string(s) { self.s_bytes(s) } else { 0 };
        let took = end
            .map(|e| (e - start).max(0) as u32)
            .unwrap_or_else(|| n.saturating_sub(start.max(0) as u32));
        self.charge_bytes(took.min(n));
        if self.str_indexable(s) {
            let n = self.s_bytes(s) as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                // THE RANGE IS NAMED. This said only "bad substring range" while
                // both ports said `subs 5..1 of 3`, so the three runtimes refused
                // the same call with different text. The ports' form is the more
                // useful of the two and native's exception CLASS is the more
                // precise, so each side took the other's better half.
                let msg = alloc::format!("subs {start}..{e} of {n}");
                return self.throw_str("StringIndexOutOfBoundsException", &msg);
            }
            let owned: alloc::string::String = {
                let mut t = crate::rt::sbuf();
                let bytes: &[u8] = if s.is_inline_str() {
                    s.inline_bytes(&mut t)
                } else {
                    str_bytes(&self.gc.sp, s.as_heap())
                };
                core::str::from_utf8(&bytes[start as usize..e as usize]).unwrap_or("").into()
            };
            return self.string(&owned);
        }
        let mut buf = crate::rt::sbuf();
        let owned = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else if s.is_heap() && ty(&self.gc.sp, s.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, s.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            let t = core::str::from_utf8(b).unwrap_or("");
            let n = t.chars().count() as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                // THE RANGE IS NAMED. This said only "bad substring range" while
                // both ports said `subs 5..1 of 3`, so the three runtimes refused
                // the same call with different text. The ports' form is the more
                // useful of the two and native's exception CLASS is the more
                // precise, so each side took the other's better half.
                let msg = alloc::format!("subs {start}..{e} of {n}");
                return self.throw_str("StringIndexOutOfBoundsException", &msg);
            }
            let out: alloc::string::String =
                t.chars().skip(start as usize).take((e - start) as usize).collect();
            out
        };
        self.string(&owned)
    }

    pub fn keyword_from_values(&mut self, ns: Value, name: Value) -> Value {
        let mut bn = crate::rt::sbuf();
        let mut bm = crate::rt::sbuf();
        let owned_ns: Option<alloc::string::String> = if ns.is_nil() {
            None
        } else {
            Some(self.as_str(ns, &mut bn).unwrap_or("").into())
        };
        let owned_name: alloc::string::String = if self.is_string(name) {
            self.as_str(name, &mut bm).unwrap_or("").into()
        } else if self.is_keyword(name) || self.is_symbol(name) {
            let n = self.name_of(name);
            let mut b2 = crate::rt::sbuf();
            self.as_str(n, &mut b2).unwrap_or("").into()
        } else {
            return NIL;
        };
        self.keyword(owned_ns.as_deref(), &owned_name)
    }

    pub fn symbol_from_values(&mut self, ns: Value, name: Value) -> Value {
        let mut bn = crate::rt::sbuf();
        let mut bm = crate::rt::sbuf();
        let owned_ns: Option<alloc::string::String> = if ns.is_nil() {
            None
        } else {
            Some(self.as_str(ns, &mut bn).unwrap_or("").into())
        };
        let owned_name: alloc::string::String = if self.is_string(name) {
            self.as_str(name, &mut bm).unwrap_or("").into()
        } else if self.is_symbol(name) || self.is_keyword(name) {
            let n = self.name_of(name);
            let mut b2 = crate::rt::sbuf();
            self.as_str(n, &mut b2).unwrap_or("").into()
        } else {
            return NIL;
        };
        self.symbol(owned_ns.as_deref(), &owned_name)
    }

    // --- numbers to and from text ---------------------------------------------

    /// A validated decimal as a double, over the byte range `from`..`to`.
    ///
    /// The other half of the hole `f64_digits` opens. `strnum.kin` has already
    /// decided this run is a number and which characters it may contain, so
    /// the parse cannot fail and `unwrap_or` is not hiding anything -- what it
    /// stands in for is the case this runtime used to decide by itself, when
    /// `str::parse::<f64>` was the whole of what flint considered a number and
    /// therefore accepted `inf` and `infinity` as well.
    pub fn f64_of_str(&mut self, s: Value, from: u32, to: u32) -> f64 {
        let mut buf = crate::rt::sbuf();
        let t: alloc::string::String = match self.as_str(s, &mut buf) {
            Some(x) => x.into(),
            None => return 0.0,
        };
        t.get(from as usize..to as usize)
            .and_then(|x| x.parse::<f64>().ok())
            .unwrap_or(0.0)
    }

    /// The shortest decimal that reads back as `d`, as characters in `c`.
    ///
    /// THE WHOLE HOLE. Rendering used to live here, and in two more places in
    /// the ports, and the three of them disagreed with each other and with
    /// Clojure about when to use an exponent and what to call an infinity.
    /// `dblstr.kin` decides all of that now for every runtime; what stays here
    /// is the one thing worth borrowing from a host number library, which is
    /// the shortest round-tripping digits. `{:e}` rather than `{}` so the
    /// string is short for every magnitude -- `{}` spells `1e-300` with three
    /// hundred zeros in it.
    ///
    /// Bytes straight into the buffer: the output is ASCII by construction,
    /// and a flint string here would only have to be decoded back out.
    pub fn f64_digits(&mut self, c: u32, d: f64) {
        let s = alloc::format!("{:e}", d);
        for b in s.bytes() {
            self.cps_put(c, b as u32);
        }
    }


    /// Concatenate a collection of strings in one pass. Without this, building
    /// a string by repeated `str` is quadratic, which shows up immediately in
    /// the reader when the compiler compiles itself.
    pub fn join_strings(&mut self, coll: Value) -> Value {
        let base = self.mark();
        let s = self.seq(coll);
        let si = self.push(s);
        let mut out = alloc::string::String::new();
        let mut i = 0u64;
        while !self.r(si).is_nil() {
            // CHARGED AND CHECKED INSIDE THE LOOP. `string_from_parts` charges
            // for the bytes at the end, which bills correctly and bounds
            // nothing: this walked 300 000 elements 2 698 029 steps past an
            // exhausted budget before anyone looked, because a counter nobody
            // reads until the next interpreter instruction cannot stop a native
            // that never reaches one.
            if !self.charge_tick(i, 1, "str-join") {
                self.pop_to(base);
                return NIL;
            }
            i += 1;
            let x = self.first(self.r(si));
            // A ROPE IS A STRING. `as_str` borrows and so cannot materialise
            // one -- it returns `None` by design -- and this read that as "not
            // a string" and threw. Nothing noticed while `subs` returned flat
            // strings; the moment `subs` started SHARING and handed back a
            // rope, joining its results stopped working. The tier is supposed
            // to be invisible, so walk it.
            if self.is_rope(x) {
                let bs = self.s_to_vec(x);
                out.push_str(core::str::from_utf8(&bs).unwrap_or(""));
            } else {
                let mut b = crate::rt::sbuf();
                match self.as_str(x, &mut b) {
                    Some(t) => out.push_str(t),
                    None => {
                        self.pop_to(base);
                        return self.throw_str("ClassCastException", "str-join wants strings");
                    }
                }
            }
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        self.pop_to(base);
        self.string(&out)
    }

    /// Byte offset -> code-point index search. Returns nil when absent.
    ///
    /// ONE LINE, because the search itself is `kin/ropefind.kin` now and every
    /// runtime runs that same source. What was here was a THIRD algorithm:
    /// this one flattened the haystack with `string_arg` and searched UTF-8,
    /// the JVM built a `java.lang.String` and searched UTF-16, and the CLR
    /// built a .NET `string` and then allocated `h.Substring(0, i)` just to
    /// count code points. Three implementations over three representations,
    /// agreeing on the answer and on nothing else -- which is why the same
    /// program cost different gas on each, and why no charge could be moved to
    /// fix it. A rope is the only representation all of them share.
    ///
    /// THE FLATTEN IS GONE WITH THEM. `DECISIONS.md#strings-and-matching` lists `index-of`
    /// under what must walk the structure rather than materialise it, and
    /// warned that "a rope that flattens on every `index-of` passes every
    /// correctness test and is slower than the flat string it replaced".
    ///
    /// `from` IS CLAMPED HERE because kin's `I32` is unsigned on all three
    /// targets, so a negative `from` has to lose its sign where the sign still
    /// exists.
    pub fn str_index_of(&mut self, haystack: Value, needle: Value, from: i64) -> Value {
        if !self.is_string(haystack) || !self.is_string(needle) {
            return self.throw_str("ClassCastException", "not a string");
        }
        let skip = from.max(0).min(u32::MAX as i64) as u32;
        self.s_index_of(haystack, needle, skip)
    }

    /// The UTF-8 bytes of a string, as a vector of integers. The image writer
    /// needs this when the compiler is hosted on flint.
    pub fn string_bytes_vector(&mut self, s: Value) -> Value {
        // `append_bytes` WALKS a rope; `string_arg` flattens it. Both produce
        // the same bytes and only one of them replaces the tree with a copy
        // that every later read then uses.
        let n = if self.is_string(s) { self.s_bytes(s) } else { 0 };
        // Worst of the lot before this: 11 937 109 steps past the limit, because
        // it billed the whole string and then built a vector of every byte.
        if !self.charge_checked(n as u64, "str-bytes") {
            return crate::value::NIL;
        }
        let mut buf = crate::rt::sbuf();
        let owned: alloc::vec::Vec<u8> = if self.is_rope(s) {
            // WALK, do not flatten. Both produce the same bytes; only one of
            // them replaces the tree with a copy that every later read uses.
            self.s_to_vec(s)
        } else {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else if s.is_heap() && ty(&self.gc.sp, s.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, s.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            b.to_vec()
        };
        let base = self.mark();
        let mut v = self.empty_vec();
        let vi = self.push(v);
        for byte in owned {
            let nv = self.vec_conj(self.r(vi), Value::fixnum(byte as i64));
            self.set_r(vi, nv);
        }
        v = self.r(vi);
        self.pop_to(base);
        v
    }

    /// An array-map built from a flat k,v collection, preserving order and not
    /// promoting whatever its size.
    // `ordered_map` and `new_volatile` are GENERATED, from `kin/mapmake.kin`.
    // Both ports already said these line for line, so generating them found
    // nothing -- which is the point of doing it last rather than not at all.

    // --- opaque values (DECISIONS.md#opaque-values) -------------------------------------

    /// The next opaque identity, and step the counter.
    ///
    /// A generated source can neither hold the counter nor increment it in
    /// place, so it asks for one. Identities are never reused: `opaque-values` says an
    /// opaque value IS its identity, and a recycled id would make two of them
    /// equal.
    pub fn take_opaque_id(&mut self) -> i64 {
        let id = self.next_opaque;
        self.next_opaque = self.next_opaque.wrapping_add(1);
        id as i64
    }

    // --- diagnostics ------------------------------------------------------------

    pub fn gc_stats_map(&mut self) -> Value {
        let s = self.gc.stats;
        let young = self.gc.young_used();
        let pairs: [(&str, i64); 8] = [
            ("minor", s.minor as i64),
            ("major", s.major as i64),
            ("bytes-allocated", s.bytes_allocated as i64),
            ("bytes-copied", s.bytes_copied as i64),
            ("bytes-promoted", s.bytes_promoted as i64),
            ("young-used", young as i64),
            ("old-live", self.gc.old_live() as i64),
            ("old-capacity", self.gc.old_capacity() as i64),
        ];
        let base = self.mark();
        let m = self.empty_map();
        let mi = self.push(m);
        for (k, v) in pairs {
            let kv = self.keyword(None, k);
            let ki = self.push(kv);
            let vv = self.integer(v);
            let kv = self.r(ki);
            self.pop_to(ki);
            let nm = self.map_assoc(self.r(mi), kv, vv);
            self.set_r(mi, nm);
        }
        let out = self.r(mi);
        self.pop_to(base);
        out
    }
}


// --- transient maps and sets -------------------------------------------------

impl Rt {
    // `map_transient`, `tmap_get`, `tmap_assoc`, `tmap_dissoc`, `tmap_count`,
    // `tmap_persistent`, `set_transient`, `tset_conj`, `tset_disj`,
    // `tset_count`, `tset_get` and `tset_persistent` are GENERATED, from
    // `kin/maptrans.kin`.
}

/// A scratch buffer type used by string builtins.
pub const SBUF_LEN: usize = INLINE_MAX;
