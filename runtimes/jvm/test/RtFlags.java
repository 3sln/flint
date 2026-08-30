import com.flint.rt.*;
import java.nio.file.*;

/// What the compiler decided, and what this runtime does about it.
///
/// `:optimize [perf]` has to MEAN the same thing on every runtime and cannot be
/// carried the same way on any two: on wasm it changes the artifact, and the
/// ported runtimes read a flag out of the image.
///
/// `aot=false` here is not a gap that was overlooked. The boxed port compiled
/// arities to host bytecode at load time, and it could, because every value was
/// already a host object. This runtime's values are NaN-boxed longs in a flat
/// heap, so the same feature is not a port of that code but a new backend
/// against a different representation. `doc/ports.md` records the trade.
///
/// The assertion that survives is the one worth keeping: the decision REACHES
/// the runtime. A flag the compiler writes and no runtime reads is a flag that
/// can silently stop being written.
public class RtFlags {
  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(1024 * 1024, 16L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("FAIL not a flint image"); System.exit(1); }
    System.out.println("flags=" + img.flags + " aot=false");
  }
}
