import com.flint.Hash;

/// The hash, against the SAME pinned vectors `runtime/src/hash.rs` asserts.
///
/// Copied from that file rather than generated from this one, which is the
/// whole point: a port that agrees with itself proves nothing. These numbers
/// were solved against real JVM Clojure, so passing them means all three agree.
public class HashTest {
    static int failures = 0;
    static void eq(int got, int want, String what) {
        boolean ok = got == want;
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what
            + (ok ? "" : "  want " + want + ", got " + got));
        if (!ok) failures++;
    }

    public static void main(String[] a) {
        eq(Hash.hashLong(0), 0, "hash 0");
        eq(Hash.hashLong(1), 1392991556, "hash 1");
        eq(Hash.hashLong(-1), 1651860712, "hash -1");
        eq(Hash.hashLong(42), 1871679806, "hash 42");
        eq(Hash.hashLong(12345678901234L), -1096982217, "hash a big long");
        eq(Hash.hashLong(Long.MAX_VALUE), -2106506049, "hash Long.MAX_VALUE");
        eq(Hash.hashLong(Long.MIN_VALUE), 1366273829, "hash Long.MIN_VALUE");

        eq(Hash.hashDouble(0.0), 0, "hash 0.0");
        eq(Hash.hashDouble(-0.0), 0, "hash -0.0 as 0.0");
        eq(Hash.hashDouble(1.0), 1072693248, "hash 1.0");
        eq(Hash.hashDouble(1.5), 1073217536, "hash 1.5");
        eq(Hash.hashDouble(-2.75), -1073348608, "hash -2.75");

        eq(Hash.hashString(""), 0, "hash \"\"");
        eq(Hash.hashString("a"), 1455541201, "hash \"a\"");
        eq(Hash.hashString("abc"), 74834163, "hash \"abc\"");
        eq(Hash.hashString("hello, world"), 136167191, "hash \"hello, world\"");
        // Past ASCII: the formulas run over UTF-16 code units, which is where a
        // UTF-8 runtime and a UTF-16 one silently part company.
        eq(Hash.hashString("日本語"), 1333041691, "hash a non-ASCII string");

        eq(Hash.hashSymbol(null, "a"), -482876059, "hash 'a");
        eq(Hash.hashSymbol(null, "abc"), 408495850, "hash 'abc");
        eq(Hash.hashSymbol("foo", "bar"), 254379989, "hash 'foo/bar");

        System.out.println(failures == 0 ? "\nall checks passed" : "\nFAILED");
        if (failures != 0) System.exit(1);
    }
}
