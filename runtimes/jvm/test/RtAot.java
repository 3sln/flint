import com.flint.rt.*;
import java.nio.file.*;

/// The same program interpreted and compiled, and the answers diffed.
///
/// The only thing worth asserting about a backend is that IT DID NOT CHANGE
/// THE ANSWER. A backend that is fast and wrong is worse than no backend, and
/// "it ran" does not tell them apart.
///
/// Gas is compared too, and that is not decoration. Compiled code charges per
/// CHUNK from a static instruction count, and the interpreter charges per
/// instruction -- so the two agreeing is a statement that the chunking is
/// right. `DECISIONS.md#emit-wasm-instead-of-dispatch` records the bug that found: every answer matched
/// and the counts did not, because a conditional jump in the middle of a chunk
/// left without running the rest and the chunk charged for them anyway.
public class RtAot {
  record Run(String out, long steps, int compiled, long entries) {}

  static Run go(String path, boolean aot, boolean chunkAll) throws Exception {
    Rt rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
    // A LIMIT, so `steps` IS MAINTAINED AT ALL. With no gas limit and no
    // peer thread, `run`'s dispatch is the free `NoBudget` loop
    // (`DECISIONS.md#resource-limits`), and the per-instruction gate it compiles away
    // does not increment `steps` for ANYTHING this program's own entry
    // dispatches -- while AOT-compiled code still bills every chunk
    // unconditionally (`Aot.java`'s `rt.steps += gas`, mirroring
    // `runtime/src/aot.rs`, which has no free-loop counterpart at all). The
    // comparison below would then be "a real number against a near-zero
    // one" rather than "the same instruction count", and it would have
    // looked like a chunking bug. `test/aot.clj` hit exactly this and
    // fixed it the same way, for the same reason, on native and wasm: set
    // a limit high enough never to trip before comparing.
    // `0x7ffffff000000000` is that file's own constant, used here unchanged
    // so the two suites agree on what "high enough" means.
    rt.setGasLimit(0x7ffffff000000000L);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(path)));
    if (img == null) return new Run("FAIL not a flint image", 0, 0, 0);
    int n = aot ? rt.compileArities(chunkAll) : 0;
    Rt.aotEntries = 0;
    // THESE ARE THE INITIALISERS, so say so: `ensureStarted` is the runtime's
    // own one-shot runner, and a control plane spawned later would otherwise
    // run them a second time.
    // The runtime's own runner, not a copy of it (see `RtImage`).
    if (!rt.ensureStarted()) return new Run(why(rt), rt.steps, n, Rt.aotEntries);
    long v = rt.runProgram(rt.makeClosure(img.entry, new long[0]), new long[]{ Val.NIL });
    if (!Val.isNil(rt.thrown)) return new Run(why(rt), rt.steps, n, Rt.aotEntries);
    return new Run(Str.isString(rt, v) ? Str.text(rt, v) : rt.describe(v), rt.steps, n, Rt.aotEntries);
  }

  static String why(Rt rt) {
    long t = rt.thrown;
    if (rt.isException(t) && Str.isString(rt, rt.exMessage(t))) {
      return "threw " + Str.text(rt, rt.exKind(t)) + ": " + Str.text(rt, rt.exMessage(t));
    }
    return "threw " + rt.describe(t);
  }

  public static void main(String[] a) throws Exception {
    Run interp = go(a[0], false, false);
    Run comp = go(a[0], true, false);
    if (comp.compiled() == 0) {
      System.out.println("  FAIL nothing compiled, so this asserts nothing");
      System.exit(1);
    }
    // AND ENTERED. Compiling is not running: the first version of this port
    // left `aotIp` at NEVER, so every arity compiled and not one instruction of
    // compiled code ever executed -- and every assertion below passed, because
    // an interpreter agrees with itself. An instrument reading zero agrees with
    // everything.
    if (comp.entries() == 0) {
      System.out.println("  FAIL " + comp.compiled()
                         + " arities compiled and compiled code was never ENTERED");
      System.exit(1);
    }
    if (!interp.out().equals(comp.out())) {
      System.out.println("  FAIL compiled and interpreted disagree");
      System.out.println("        interpreted " + interp.out());
      System.out.println("        compiled    " + comp.out());
      System.exit(1);
    }
    // MAXIMAL CHUNKING as well. If a failure survives it then no boundary was
    // missing and the fault is in how an opcode is emitted; if it does not,
    // a boundary is. Those are the two halves this can be wrong in and they
    // want opposite fixes, so the bisection is worth having standing.
    Run all = go(a[0], true, true);
    if (!interp.out().equals(all.out())) {
      System.out.println("  FAIL compiled disagrees under maximal chunking");
      System.out.println("        interpreted " + interp.out());
      System.out.println("        compiled    " + all.out());
      System.exit(1);
    }
    if (interp.steps() != comp.steps()) {
      System.out.println("  FAIL the gas counts differ, so the chunking is wrong");
      System.out.println("        interpreted " + interp.steps());
      System.out.println("        compiled    " + comp.steps());
      System.exit(1);
    }
    System.out.println("  ok   " + comp.compiled() + " arities compiled, entered "
                       + comp.entries() + " times, same answer and same gas ("
                       + interp.steps() + ")");
  }
}
