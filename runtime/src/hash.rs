//! Hashing. Clojure's numbers for NUMBERS and COLLECTIONS; flint's own for
//! anything made of text.
//!
//! ```text
//!   nil                0
//!   true / false       1231 / 1237
//!   long               Murmur3.hashLong                     as Clojure
//!   double             (int)(bits ^ (bits >>> 32))          as Clojure
//!   string             hash_bytes                           flint's own
//!   symbol             hash_combine of both parts' bytes    flint's own
//!   keyword            symbol hash + 0x9e3779b9             flint's own
//!   sequential         hashOrdered   (31*h + hash(x), then mixCollHash)
//!   set                hashUnordered (sum of hashes, then mixCollHash)
//!   map                hashUnordered over entries; an entry hashes as [k v]
//! ```
//!
//! THE TEXT ROWS USED TO MATCH CLOJURE TOO, through `javaStringHashCode` and
//! `hashUnencodedChars`, both of which run over UTF-16 CODE UNITS. flint
//! strings are UTF-8, so reaching those numbers meant decoding UTF-8 and
//! re-deriving a UTF-16 view on every hash -- on the JVM, allocating an array
//! to hold it, on the path that hashes map keys.
//!
//! It bought nothing that was promised. Clojure changed its own hash in 1.6
//! and documents no stability across versions, so the numbers were never a
//! contract. What a program can rest on is that equal values hash alike, and
//! that every runtime agrees -- both of which the conform suite pins.
//!
//! The number rows above ARE still Clojure's, and cost nothing extra to keep:
//! they are bit operations on a value already in a register, with no decode
//! and no allocation behind them.

// THE CONSUMING LINE. The Murmur3 primitives are generated now, into
// `kgen/rt/hash.rs`, and they are FREE FUNCTIONS rather than methods
// -- so unlike an `impl Rt` block they have to be brought into scope. This
// re-export puts them back under `crate::hash`, which is where every call
// site in the runtime already looks for them.
pub(crate) use crate::kgen::rt::hash::*;

// AND THE TEXT HALF, from `kgen/rt/hashtext.rs`. `hash_double`, `hash_bytes`,
// `hash_symbol` and `hash_keyword` were WRITTEN OUT HERE, and identically in
// `Hash.java` and `Hash.cs`, with a comment in each port saying the three had
// to be kept in step by hand. They are one `kin/hashtext.kin` now.
//
// The three copies agreed on every number and disagreed on the INTERFACE:
// this one took `Option<&str>` and `&str` where both ports took `byte[]` and
// `null`. The generated function takes bytes on all three, and an ABSENT
// namespace is an EMPTY one -- `hash_bytes(b"")` folds over nothing, giving
// `hash_int(0)`, which short-circuits to the 0 that `ns == null ? 0` supplied.
// `kin/hashtext.drivers` derives both sides from `clojure.lang.Util` and
// prints them, so the identity is checked rather than argued.
pub(crate) use crate::kgen::rt::hashtext::*;

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
        assert_ne!(hash_symbol(b"", b"foo"), hash_keyword(b"", b"foo"));
        assert_ne!(hash_symbol(b"a", b"b"), hash_keyword(b"a", b"b"));
    }

    #[test]
    fn an_astral_name_hashes_over_its_bytes() {
        // U+1F600 is one code point, four UTF-8 bytes, and two UTF-16 units.
        // The hash is over the BYTES -- which is the whole change: no decode,
        // no surrogate pair, nothing that knows what UTF-16 is.
        let s = "\u{1F600}";
        assert_eq!(s.len(), 4, "four UTF-8 bytes");
        assert_eq!(hash_symbol(b"", s.as_bytes()), hash_combine(hash_bytes(s.as_bytes()), 0));
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
        assert_eq!(hash_symbol(b"", b"abc"), hash_symbol(b"", b"abc"));
        assert_ne!(hash_symbol(b"", b"abc"), hash_symbol(b"", b"abd"));
        assert_ne!(hash_symbol(b"", b"bar"), hash_symbol(b"foo", b"bar"));
        assert_ne!(hash_symbol(b"a", b"b"), hash_symbol(b"b", b"a"));
        // A keyword is its symbol plus the separator, and nothing else.
        assert_eq!(hash_keyword(b"foo", b"bar"),
                   hash_symbol(b"foo", b"bar").wrapping_add(0x9e3779b9));
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
        let a = entry(hash_keyword(b"", b"a"), hash_long(1));
        let b = entry(hash_keyword(b"", b"b"), hash_long(2));
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
