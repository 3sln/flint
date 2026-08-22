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

const C1: u32 = 0xcc9e2d51;
const C2: u32 = 0x1b873593;
pub const SEED: u32 = 0;

#[inline]
fn mix_k1(k1: u32) -> u32 {
    k1.wrapping_mul(C1).rotate_left(15).wrapping_mul(C2)
}
#[inline]
fn mix_h1(h1: u32, k1: u32) -> u32 {
    (h1 ^ k1).rotate_left(13).wrapping_mul(5).wrapping_add(0xe6546b64)
}
#[inline]
fn fmix(mut h1: u32, len: u32) -> u32 {
    h1 ^= len;
    h1 ^= h1 >> 16;
    h1 = h1.wrapping_mul(0x85ebca6b);
    h1 ^= h1 >> 13;
    h1 = h1.wrapping_mul(0xc2b2ae35);
    h1 ^ (h1 >> 16)
}

pub fn hash_int(input: u32) -> u32 {
    if input == 0 {
        return 0;
    }
    fmix(mix_h1(SEED, mix_k1(input)), 4)
}

pub fn hash_long(input: i64) -> u32 {
    if input == 0 {
        return 0;
    }
    let low = input as u32;
    let high = (input as u64 >> 32) as u32;
    let h1 = mix_h1(SEED, mix_k1(low));
    let h1 = mix_h1(h1, mix_k1(high));
    fmix(h1, 8)
}

pub fn hash_double(d: f64) -> u32 {
    if d == 0.0 {
        return 0; // both 0.0 and -0.0, matching Numbers.hasheq
    }
    let bits = d.to_bits();
    ((bits ^ (bits >> 32)) as u32) as i32 as u32
}

/// `boost::hash_combine`, as Clojure's `Util.hashCombine`.
#[inline]
pub fn hash_combine(seed: u32, h: u32) -> u32 {
    seed
        ^ h.wrapping_add(0x9e3779b9)
            .wrapping_add(seed << 6)
            .wrapping_add(((seed as i32) >> 2) as u32)
}

// --- UTF-16 views over UTF-8 ----------------------------------------------

/// Iterate the UTF-16 code units of a UTF-8 string.
pub struct Utf16Units<'a> {
    s: &'a str,
    iter: core::str::Chars<'a>,
    pending: u16,
}

impl<'a> Utf16Units<'a> {
    pub fn new(s: &'a str) -> Self {
        Utf16Units { s, iter: s.chars(), pending: 0 }
    }
    /// Number of UTF-16 code units, i.e. Java's `String.length()`.
    pub fn len_of(s: &str) -> usize {
        s.chars().map(|c| c.len_utf16()).sum()
    }
}

impl<'a> Iterator for Utf16Units<'a> {
    type Item = u16;
    fn next(&mut self) -> Option<u16> {
        let _ = self.s;
        if self.pending != 0 {
            let p = self.pending;
            self.pending = 0;
            return Some(p);
        }
        let c = self.iter.next()?;
        let cp = c as u32;
        if cp < 0x10000 {
            Some(cp as u16)
        } else {
            let v = cp - 0x10000;
            self.pending = (0xDC00 + (v & 0x3FF)) as u16;
            Some((0xD800 + (v >> 10)) as u16)
        }
    }
}

/// `java.lang.String.hashCode()`: s[0]*31^(n-1) + ... over UTF-16 units.
pub fn java_string_hash(s: &str) -> u32 {
    let mut h: u32 = 0;
    for u in Utf16Units::new(s) {
        h = h.wrapping_mul(31).wrapping_add(u as u32);
    }
    h
}

/// `Murmur3.hashUnencodedChars`: two UTF-16 units per murmur word.
pub fn hash_unencoded_chars(s: &str) -> u32 {
    let mut h1 = SEED;
    let mut n: u32 = 0;
    let mut prev: Option<u16> = None;
    for u in Utf16Units::new(s) {
        n += 1;
        match prev {
            None => prev = Some(u),
            Some(p) => {
                let k1 = (p as u32) | ((u as u32) << 16);
                h1 = mix_h1(h1, mix_k1(k1));
                prev = None;
            }
        }
    }
    if let Some(p) = prev {
        h1 ^= mix_k1(p as u32);
    }
    fmix(h1, 2u32.wrapping_mul(n))
}

// --- the value-level entry points ------------------------------------------

pub fn hash_string(s: &str) -> u32 {
    hash_int(java_string_hash(s))
}

/// `ns` is the *raw* Java string hash here, not the murmur'd one. That
/// asymmetry is real, and was found by solving for it against `'foo/bar`.
pub fn hash_symbol(ns: Option<&str>, name: &str) -> u32 {
    hash_combine(hash_unencoded_chars(name), ns.map_or(0, java_string_hash))
}

pub fn hash_keyword(ns: Option<&str>, name: &str) -> u32 {
    hash_symbol(ns, name).wrapping_add(0x9e3779b9)
}

pub fn mix_coll_hash(hash: u32, count: u32) -> u32 {
    fmix(mix_h1(SEED, mix_k1(hash)), count)
}

/// Running accumulator for an ordered collection: start at 1, fold, then
/// `mix_coll_hash(acc, n)`.
#[inline]
pub fn ordered_step(acc: u32, item_hash: u32) -> u32 {
    acc.wrapping_mul(31).wrapping_add(item_hash)
}
#[inline]
pub fn unordered_step(acc: u32, item_hash: u32) -> u32 {
    acc.wrapping_add(item_hash)
}

pub const HASH_TRUE: u32 = 1231;
pub const HASH_FALSE: u32 = 1237;

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

    #[test]
    fn strings_match_clojure() {
        assert_eq!(hash_string("") as i32, 0);
        assert_eq!(hash_string("a") as i32, 1455541201);
        assert_eq!(hash_string("abc") as i32, 74834163);
        assert_eq!(hash_string("hello, world") as i32, 136167191);
        // Non-ASCII: proves the UTF-16 view, since the UTF-8 bytes differ.
        assert_eq!(hash_string("日本語") as i32, 1333041691);
    }

    #[test]
    fn java_string_hash_matches() {
        assert_eq!(java_string_hash("foo") as i32, 101574);
        assert_eq!(java_string_hash("x") as i32, 120);
        assert_eq!(java_string_hash("") as i32, 0);
    }

    #[test]
    fn utf16_view_is_right_for_astral_characters() {
        // U+1F600 GRINNING FACE is one code point, two UTF-16 units.
        let s = "\u{1F600}";
        assert_eq!(s.len(), 4, "four UTF-8 bytes");
        assert_eq!(Utf16Units::len_of(s), 2, "two UTF-16 units");
        let units: alloc::vec::Vec<u16> = Utf16Units::new(s).collect();
        assert_eq!(units, alloc::vec![0xD83D, 0xDE00]);
        // Java: "😀".hashCode() == 0xD83D*31 + 0xDE00
        assert_eq!(java_string_hash(s), (0xD83Du32).wrapping_mul(31).wrapping_add(0xDE00));
    }

    #[test]
    fn symbols_and_keywords_match_clojure() {
        assert_eq!(hash_symbol(None, "a") as i32, -482876059);
        assert_eq!(hash_symbol(None, "abc") as i32, 408495850);
        assert_eq!(hash_symbol(Some("foo"), "bar") as i32, 254379989);
        assert_eq!(hash_keyword(None, "a") as i32, -2123407586);
        assert_eq!(hash_keyword(None, "abc") as i32, -1232035677);
        assert_eq!(hash_keyword(Some("foo"), "bar") as i32, -1386151538);
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
    fn map_hash_matches_clojure() {
        // {:a 1} -> unordered over entries; an entry hashes as the vector [k v]
        let entry = |kh: u32, vh: u32| {
            let acc = ordered_step(ordered_step(1, kh), vh);
            mix_coll_hash(acc, 2)
        };
        let e = entry(hash_keyword(None, "a"), hash_long(1));
        assert_eq!(mix_coll_hash(unordered_step(0, e), 1) as i32, 1772842048);

        let e2 = entry(hash_keyword(None, "b"), hash_long(2));
        let acc = unordered_step(unordered_step(0, e), e2);
        assert_eq!(mix_coll_hash(acc, 2) as i32, 161871944);
    }

    #[test]
    fn booleans() {
        assert_eq!(HASH_TRUE, 1231);
        assert_eq!(HASH_FALSE, 1237);
    }
}
