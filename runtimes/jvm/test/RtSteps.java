import com.flint.rt.*;
import java.nio.file.*;

/// The step count for an image, and nothing else.
///
/// `bin/conform-hosts` runs this for two workloads and compares the DIFFERENCE
/// against the same difference on the native runtime, which cancels whatever
/// each runtime spends starting up and leaves only what the program did.
///
/// CALLED OVER A BRIDGE, exactly as native is. The wasm side of that row runs
/// `i.run("gasmeter/small", [""])` through the ESM host, which is a message on
/// a port; this used to run `img.entry` through `runProgram`, which was the
/// model from before a sandbox became a thing you CALL
/// (`DECISIONS.md#bridges-are-the-only-door`). A measurement whose two sides
/// enter the program by different doors is not measuring the program.
///
/// It also means the INITIALISERS are not run here. They were, by hand, with
/// `rt.started = true` to stop the control plane running them twice -- which
/// is the runtime's own job (`ensureStarted`) and is what `bootSystemThread`
/// calls before it spawns anything.
public class RtSteps {
  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("-1"); return; }
    // A LIMIT, not none: an unbudgeted sandbox deliberately keeps no counter.
    rt.setGasLimit(0x7ffffff0L);
    // NAMED BY THE CALLER, as the host names it: the wasm side asks for
    // `gasmeter/small` by name (`DECISIONS.md#structured-ports`), so this
    // takes the same name as an argument rather than guessing one from the
    // file. A harness that derives the name from the path is one rename away
    // from measuring nothing and still printing a number.
    if (a.length < 2) { System.err.println("usage: RtSteps <image> <ns/fn>"); System.exit(2); }
    HostCall.call(rt, a[1], new String[]{ "" });
    // STEPS AND PREEMPTIONS, because the comparison downstream needs both:
    // a preemption is billed work and two runtimes may serve a different
    // number of them for the same program.
    System.out.println(rt.steps + " " + rt.restores);
    // The per-type billed-allocation histogram, for the rows that compare
    // gas across runtimes. Only non-zero types, so the line stays readable.
    if (System.getenv("FLINT_ALLOC_HIST") != null) {
      StringBuilder sb = new StringBuilder();
      for (int t = 0; t < 64; t++)
        if (rt.allocN[t] != 0) sb.append(t).append(':').append(rt.allocN[t])
                                 .append('/').append(rt.allocGas[t]).append(' ');
      System.out.println("HIST " + sb);
      long ag = 0; for (long g : rt.allocGas) ag += g;
      System.out.println("SPLIT instrs=" + rt.instrs + " allocgas=" + ag
                         + " other=" + (rt.steps - rt.instrs - ag)
                         + " bytesgas=" + rt.chargeBytesGas);
    }
  }
}
