import com.flint.rt.*;
import java.nio.file.*;

/// The step count for an image, and nothing else.
///
/// `bin/conform-hosts` runs this for two workloads and compares the DIFFERENCE
/// against the same difference on the native runtime, which cancels whatever
/// each runtime spends starting up and leaves only what the program did.
public class RtSteps {
  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("-1"); return; }
    // A LIMIT, not none: an unbudgeted sandbox deliberately keeps no counter.
    rt.setGasLimit(0x7ffffff0L);
    for (int fn : img.init) rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
    rt.runProgram(rt.makeClosure(img.entry, new long[0]), new long[]{ Val.NIL });
    System.out.println(rt.steps);
  }
}
