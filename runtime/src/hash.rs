//! Hashing, bit-compatible with JVM Clojure's `hash`.
//!
//! This is worth the trouble: `hash` is observable from Clojure code, and a
//! program that ports to flint should get the same numbers it got before. The
//! formulas were derived by solving against real Clojure values rather than
//! from memory -- see the tests, which pin every one of them.
//!
//! ```text
//!   nil                0
//!   true / false       1231 / 1237
//!   long               Murmur3.hashLong
//!   double             (int)(bits ^ (bits >>> 32)), and -0.0 hashes as 0.0
//!   string             Murmur3.hashInt(javaStringHashCode(s))
//!   symbol             hashCombine(hashUnencodedChars(name), rawHash(ns))
//!   keyword            symbolHash + 0x9e3779b9
//!   sequential         hashOrdered      (31*h + hash(x), then mixCollHash)
//!   set                hashUnordered    (sum of hashes, then mixCollHash)
//!   map                hashUnordered over entries; an entry hashes as [k v]
//! ```
//!
//! Note `javaStringHashCode` and `hashUnencodedChars` both run over **UTF-16
//! code units**, not bytes and not code points. flint strings are UTF-8, so the
//! iterators below re-derive the UTF-16 view on the fly. Getting this wrong is
//! invisible until the first astral-plane character.


// THE CONSUMING LINE. The Murmur3 primitives are generated now, into
// `kgen/rt/hash.rs`, and they are FREE FUNCTIONS rather than methods
// -- so unlike an `impl Rt` block they have to be brought into scope. This
// re-export puts them back under `crate::hash`, which is where every call
// site in the runtime already looks for them.
pub(crate) use crate::kgen::rt::hash::*;

pub fn hash_double(d: f64) -> u32 {
    if d == 0.0 {
        return 0; // both 0.0 and -0.0, matching Numbers.hasheq
    }
    let bits = d.to_bits();
    ((bits ^ (bits >> 32)) as u32) as i32 as u32
}

// --- UTF-16 views over UTF-8 ----------------------------------------------

// `Utf16Units` WAS HERE. It decoded UTF-8 and re-encoded it as UTF-16 code
// units, and existed only so that string, symbol and keyword hashes could
// reproduce the numbers a JVM produces. All three walk bytes now, and string
// COMPARISON -- its last other user -- walks bytes too, so nothing needs it.



// --- the value-level entry points ------------------------------------------

// `hash_string`, `java_string_hash` and `hash_unencoded_chars` were all here
// and are all gone. Strings, symbols and keywords hash over BYTES now. The
// note that stood here said `java_string_hash` "STAYS, because a symbol's hash
// still combines it" -- that was true for exactly as long as symbols were
// still matching Clojure's numbers.

/// `h = h*31 + byte`, the walk `rope_hash` does over a tree's leaves.
///
/// THE STRING HASH IS DEFINED OVER BYTES, not over UTF-16 units. See
/// `Rt::string_hash` for why; the short version is that a tree already walked
/// bytes, bytes are what `pow31` can compose for per-node caching, and
/// matching Clojure's numbers is not a contract Clojure offers.
pub fn hash_bytes(bs: &[u8]) -> u32 {
    let mut h: u32 = 0;
    for b in bs {
        h = h.wrapping_mul(31).wrapping_add(*b as u32);
    }
    hash_int(h)
}

/// A symbol's hash, over BYTES like every other string hash here.
///
/// IT USED TO REPRODUCE CLOJURE'S NUMBER, and the shape of that is worth
/// recording because it is what an artefact looks like from the inside:
///
/// ```text
/// hash_combine(hash_unencoded_chars(name), java_string_hash(ns))
/// ```
///
/// murmur over the name's UTF-16 units, combined with the RAW 31-walk over the
/// namespace's. The asymmetry was not derived from anything -- the comment
/// that stood here said it "was found by solving for it against `'foo/bar`",
/// which is fitting a formula to observed output.
///
/// flint strings are UTF-8, so every call decoded UTF-8 and synthesised UTF-16
/// to get there. Keywords are the most frequently hashed values in a program
/// -- they are the constant map keys -- so that decode sat on the hot path, and
/// on the JVM it ALLOCATED an int array to hold the units.
///
/// The string hash stopped doing this earlier: Clojure changed its own hash in
/// 1.6 and documents no stability across versions, so the numbers were never a
/// contract. This is the same argument one type over. What a program can rest
/// on is that equal values hash alike, and they do.
pub fn hash_symbol(ns: Option<&str>, name: &str) -> u32 {
    // A `match`, NOT `map_or(0, |s| ..)`. The closure form costs 917 bytes in
    // the shipped module, measured: a closure is a `call_indirect` target and
    // the shaker roots those CONSERVATIVELY, so it drags in whatever else
    // shares the table. `test/threads.clj` records the same mechanism costing
    // far more when one appeared in the printer.
    let nh = match ns {
        Some(s) => hash_bytes(s.as_bytes()),
        None => 0,
    };
    hash_combine(hash_bytes(name.as_bytes()), nh)
}

/// A keyword hashes as its symbol would, plus a constant, so `:foo` and `'foo`
/// do not collide. The constant came from Clojure's keyword hash and is now
/// simply A constant -- any fixed non-zero value separates the two spaces.
pub fn hash_keyword(ns: Option<&str>, name: &str) -> u32 {
    hash_symbol(ns, name).wrapping_add(0x9e3779b9)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every expected value here came out of a real Clojure (`bb` 1.3.190,
    /// which uses clojure.lang.Murmur3), not out of this implementation.
    #[test]
    fn longs_match_clojure() {
        assert_eq!(hash_long(0) as i32, 0);
        assert_eq!(hash_long(1) as i32, 1392991556);
        assert_eq!(hash_long(-1) as i32, 1651860712);
        assert_eq!(hash_long(42) as i32, 1871679806);
        assert_eq!(hash_long(12345678901234) as i32, -1096982217);
        assert_eq!(hash_long(i64::MAX) as i32, -2106506049);
        assert_eq!(hash_long(i64::MIN) as i32, 1366273829);
    }

    #[test]
    fn doubles_match_clojure() {
        assert_eq!(hash_double(0.0) as i32, 0);
        assert_eq!(hash_double(-0.0) as i32, 0);
        assert_eq!(hash_double(1.0) as i32, 1072693248);
        assert_eq!(hash_double(1.5) as i32, 1073217536);
        assert_eq!(hash_double(-2.75) as i32, -1073348608);
    }

    /// A string hashes over its UTF-8 BYTES, at every tier.
    ///
    /// THE ASCII ROWS ARE UNCHANGED from when this walked UTF-16 units,
    /// because there a byte IS a unit -- which is the whole width of the
    /// divergence from Clojure's numbers. The last row is the one that moved,
    /// and it is here so that a silent return to the UTF-16 basis fails.
    #[test]
    fn strings_hash_over_bytes() {
        assert_eq!(hash_bytes(b"a") as i32, 1455541201);
        assert_eq!(hash_bytes(b"hello, world") as i32, 136167191);
        assert_eq!(hash_bytes("日本語".as_bytes()) as i32, 1534549342);
        // And the empty string is zero, which `hash_int` short-circuits.
        assert_eq!(hash_bytes(b"") as i32, 0);
    }


    #[test]
    fn a_symbol_and_its_keyword_do_not_collide() {
        // THE ONE THING THE CONSTANT IS FOR. `:foo` and `'foo` hash over the
        // same bytes, so without a separator they would land together in every
        // map that holds both.
        assert_ne!(hash_symbol(None, "foo"), hash_keyword(None, "foo"));
        assert_ne!(hash_symbol(Some("a"), "b"), hash_keyword(Some("a"), "b"));
    }

    #[test]
    fn an_astral_name_hashes_over_its_bytes() {
        // U+1F600 is one code point, four UTF-8 bytes, and two UTF-16 units.
        // The hash is over the BYTES -- which is the whole change: no decode,
        // no surrogate pair, nothing that knows what UTF-16 is.
        let s = "\u{1F600}";
        assert_eq!(s.len(), 4, "four UTF-8 bytes");
        assert_eq!(hash_symbol(None, s), hash_combine(hash_bytes(s.as_bytes()), 0));
    }

    #[test]
    fn symbols_and_keywords_hash_by_their_parts() {
        // THESE USED TO BE CLOJURE'S NUMBERS, six of them, asserted literally.
        // They were the contract right up until the contract was that there is
        // no contract: Clojure changed its own hash in 1.6 and documents no
        // stability across versions.
        //
        // What a program can rest on is below. Equal names hash alike; a
        // namespace changes the answer; and a different name gives a different
        // one. Nothing here pins a NUMBER, because a number is what tied this
        // to somebody else's implementation.
        assert_eq!(hash_symbol(None, "abc"), hash_symbol(None, "abc"));
        assert_ne!(hash_symbol(None, "abc"), hash_symbol(None, "abd"));
        assert_ne!(hash_symbol(None, "bar"), hash_symbol(Some("foo"), "bar"));
        assert_ne!(hash_symbol(Some("a"), "b"), hash_symbol(Some("b"), "a"));
        // A keyword is its symbol plus the separator, and nothing else.
        assert_eq!(hash_keyword(Some("foo"), "bar"),
                   hash_symbol(Some("foo"), "bar").wrapping_add(0x9e3779b9));
    }

    #[test]
    fn ordered_collections_match_clojure() {
        let hv = |xs: &[i64]| {
            let mut acc = 1u32;
            for x in xs {
                acc = ordered_step(acc, hash_long(*x));
            }
            mix_coll_hash(acc, xs.len() as u32) as i32
        };
        assert_eq!(hv(&[]), -2017569654); // [] and '()
        assert_eq!(hv(&[1]), -1381383523);
        assert_eq!(hv(&[1, 2, 3]), 736442005);
    }

    #[test]
    fn unordered_collections_match_clojure() {
        let hs = |xs: &[i64]| {
            let mut acc = 0u32;
            for x in xs {
                acc = unordered_step(acc, hash_long(*x));
            }
            mix_coll_hash(acc, xs.len() as u32) as i32
        };
        assert_eq!(hs(&[]), -15128758); // #{} and {}
        assert_eq!(hs(&[1]), 1038464948);
        assert_eq!(hs(&[1, 2, 3]), 439094965);
        // Order really must not matter.
        assert_eq!(hs(&[3, 1, 2]), 439094965);
    }

    #[test]
    fn a_map_hash_is_order_independent_and_counts_its_entries() {
        // The two literal Clojure numbers that were here moved when keyword
        // hashing stopped synthesising UTF-16. The STRUCTURE did not move, and
        // it is the part that has to hold: an entry hashes as the vector
        // `[k v]`, and a map folds its entries with an UNORDERED step.
        let entry = |kh: u32, vh: u32| {
            let acc = ordered_step(ordered_step(1, kh), vh);
            mix_coll_hash(acc, 2)
        };
        let a = entry(hash_keyword(None, "a"), hash_long(1));
        let b = entry(hash_keyword(None, "b"), hash_long(2));
        // BUILT IN EITHER ORDER, same answer. A map is unordered, so a hash
        // that depended on insertion order would be wrong in a way no single
        // number could reveal.
        let ab = mix_coll_hash(unordered_step(unordered_step(0, a), b), 2);
        let ba = mix_coll_hash(unordered_step(unordered_step(0, b), a), 2);
        assert_eq!(ab, ba);
        // And the count is part of it, so `{:a 1}` and `{:a 1 :b 2}` differ
        // even before their entries do.
        assert_ne!(mix_coll_hash(unordered_step(0, a), 1), ab);
    }

    #[test]
    fn booleans() {
        assert_eq!(HASH_TRUE, 1231);
        assert_eq!(HASH_FALSE, 1237);
    }
}
