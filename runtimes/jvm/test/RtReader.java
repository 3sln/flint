import com._3sln.flint.kgen.rt.Formsenc;
import com._3sln.flint.kgen.rt.Transients;
import com._3sln.flint.kgen.rt.Bytecore;
import com._3sln.flint.kgen.rt.Vecread;
import com.flint.rt.*;
import java.nio.file.*;
import java.util.*;

/// The kin reader on the JVM, held to the guest reader's BYTES
/// (`DECISIONS.md#namespaces-over-the-system-port`, migration step 1).
///
/// `cli/src/kin_reader_test.rs` writes the reference -- every source the guest
/// read, under each option set, and what it answered -- to a directory with a
/// manifest; this reads each source again with the generated
/// `Formsenc.readForms` and compares. A file the guest could not read must fail
/// here with the same message. `bin/check-reader` runs it.
public class RtReader {
  static final Map<String, String[]> MODES = Map.of(
      "deferred", new String[0],
      "default", new String[] {"flint", "flint/check", "flint/nested"},
      "perf", new String[] {"flint", "flint/nested"});

  static long keyword(Rt rt, String f) {
    int at = f.indexOf('/');
    return at < 0 ? Str.keyword(rt, null, f) : Str.keyword(rt, f.substring(0, at), f.substring(at + 1));
  }

  public static void main(String[] a) throws Exception {
    Path dir = Paths.get(a[0]);
    int compared = 0, differ = 0;
    for (String line : Files.readAllLines(dir.resolve("manifest.tsv"))) {
      if (line.isEmpty()) continue;
      String[] f = line.split("\t");
      String tag = f[0], mode = f[1], name = f[2];
      String text = Files.readString(dir.resolve(tag + ".src"));
      Path forms = dir.resolve(tag + ".forms");
      Rt rt = new Rt(4 * 1024 * 1024, 512L * 1024 * 1024);
      int base = rt.mark();
      int si = rt.push(Str.of(rt, text));
      int fi = rt.push(Str.of(rt, name));
      long feats = Val.NIL;
      if (!mode.equals("deferred")) {
        int ti = rt.push(Transients.toTransient(rt, Sets.empty(rt)));
        for (String k : MODES.get(mode)) {
          long kw = keyword(rt, k);
          rt.setR(ti, Transients.transientConj(rt, rt.r(ti), kw));
        }
        feats = Transients.toPersistent(rt, rt.r(ti));
      }
      int fsi = rt.push(feats);
      long out = Formsenc.readForms(rt, rt.r(si), rt.r(fi), rt.r(fsi), Val.NIL, !name.endsWith(".fln"), 1);
      int oi = rt.push(out);
      compared++;
      String problem = null;
      if (Bytecore.isBytes(rt, rt.r(oi))) {
        byte[] got = Bytes.toArray(rt, rt.r(oi));
        if (!Files.exists(forms)) problem = "the guest failed, the JVM read it";
        else if (!Arrays.equals(got, Files.readAllBytes(forms))) {
          byte[] want = Files.readAllBytes(forms);
          int at = 0;
          while (at < Math.min(got.length, want.length) && got[at] == want[at]) at++;
          problem = "bytes differ at " + at + " (" + want.length + " against " + got.length + ")";
        }
      } else if (Val.isNil(out)) {
        problem = "the reader answered nil";
      } else {
        String msg = Str.text(rt, Vecread.vecNth(rt, rt.r(oi), 0, Val.NIL));
        Path err = dir.resolve(tag + ".err");
        if (!Files.exists(err)) problem = "the JVM failed, the guest read it: " + msg;
        else if (!Files.readString(err).equals(msg)) problem = "a different error: " + msg;
      }
      rt.popTo(base);
      if (problem != null) {
        differ++;
        if (differ <= 40) System.out.println("  DIFF " + mode + " " + name + ": " + problem);
      }
    }
    System.out.println("jvm kin reader: " + compared + " reads compared, " + differ + " differ");
    System.exit(differ == 0 && compared > 0 ? 0 : 1);
  }
}
