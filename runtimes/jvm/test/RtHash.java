import com.flint.rt.Hash;
import java.nio.charset.StandardCharsets;

/// The ported hash, against the numbers a REAL CLOJURE produced.
///
/// These are not this implementation's own output recorded as a baseline --
/// they are the values in `runtime/src/hash.rs`'s tests, which its comment says
/// came out of `bb` 1.3.190 using `clojure.lang.Murmur3`. A test written the
/// other way round would pass for any consistent-but-wrong hash, and the whole
/// point of the exercise is that a program ported to flint gets the numbers it
/// got before.
///
/// The astral-plane cases are the ones worth having: the formulas run over
/// UTF-16 CODE UNITS, so a surrogate pair contributes two, and a port that
/// walked code points or bytes agrees with Clojure on ASCII and diverges the
/// first time somebody writes an emoji.
public class RtHash {
  static int fails = 0;

  static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

  static void eq(String what, int got, int want) {
    if (got != want) {
      System.out.println("  FAIL " + what + ": got " + got + ", clojure says " + want);
      fails++;
    }
  }

  public static void main(String[] a) {
    eq("(hash 0)", com._3sln.flint.kgen.rt.Hash.hashLong(0), 0);
    eq("(hash 1)", com._3sln.flint.kgen.rt.Hash.hashLong(1), 1392991556);
    eq("(hash -1)", com._3sln.flint.kgen.rt.Hash.hashLong(-1), 1651860712);
    eq("(hash 42)", com._3sln.flint.kgen.rt.Hash.hashLong(42), 1871679806);
    eq("(hash 12345678901234)", com._3sln.flint.kgen.rt.Hash.hashLong(12345678901234L), -1096982217);
    eq("(hash Long/MAX_VALUE)", com._3sln.flint.kgen.rt.Hash.hashLong(Long.MAX_VALUE), -2106506049);
    eq("(hash Long/MIN_VALUE)", com._3sln.flint.kgen.rt.Hash.hashLong(Long.MIN_VALUE), 1366273829);
    System.out.println("  ok   longs hash as Clojure hashes them");

    eq("(hash 0.0)", Hash.hashDouble(0.0), 0);
    eq("(hash -0.0)", Hash.hashDouble(-0.0), 0);
    eq("(hash 1.0)", Hash.hashDouble(1.0), 1072693248);
    eq("(hash 1.5)", Hash.hashDouble(1.5), 1073217536);
    eq("(hash -2.75)", Hash.hashDouble(-2.75), -1073348608);
    System.out.println("  ok   doubles too, and -0.0 hashes as 0.0");

    eq("(hash \"\")", Hash.hashBytes(b("")), 0);
    eq("(hash \"a\")", Hash.hashBytes(b("a")), 1455541201);
    eq("(hash \"abc\")", Hash.hashBytes(b("abc")), 74834163);
    eq("(hash \"hello, world\")", Hash.hashBytes(b("hello, world")), 136167191);
    eq("(hash \"日本語\")", Hash.hashBytes(b("日本語")), 1534549342);
    System.out.println("  ok   strings, over UTF-8 BYTES at every tier");
    // The ASCII rows above are UNCHANGED from when this walked UTF-16 units,
    // because there a byte IS a unit. Only the last row moved, which is the
    // whole width of the divergence from Clojure's numbers.

    eq("(hash 'a)", Hash.hashSymbol(null, b("a")), 849029948);
    eq("(hash 'abc)", Hash.hashSymbol(null, b("abc")), -1195848122);
    eq("(hash 'foo/bar)", Hash.hashSymbol(b("foo"), b("bar")), 1403326929);
    eq("(hash :a)", Hash.hashKeyword(null, b("a")), -791501579);
    eq("(hash :abc)", Hash.hashKeyword(null, b("abc")), 1458587647);
    eq("(hash :foo/bar)", Hash.hashKeyword(b("foo"), b("bar")), -237204598);
    System.out.println("  ok   symbols and keywords, over BYTES like strings");

    // AN ASTRAL CHARACTER USED TO BE CHECKED AS A SURROGATE PAIR here, because
    // the hash ran over UTF-16 units and counting a pair as one unit would have
    // been stable and wrong. It runs over UTF-8 bytes now, so what matters is
    // that four bytes are four bytes.
    eq("an emoji hashes over its four UTF-8 bytes", Hash.hashBytes(b("\uD83D\uDE00")),
       Hash.hashBytes(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}));
    System.out.println("  ok   an astral-plane character is FOUR BYTES");

    if (fails > 0) { System.out.println("  " + fails + " failed"); System.exit(1); }
  }
}
