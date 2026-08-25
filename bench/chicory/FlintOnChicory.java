// flint on Chicory: a wasm interpreter written in Java, so this is flint's
// interpreter running inside one. `doc/decisions/0018` says this row decides
// whether `0010`'s tier 1 -- an SDK over the wasm module -- is viable on the
// JVM, or whether porting the VM is the route rather than a later luxury.
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import java.nio.file.*;

public class FlintOnChicory {
  public static void main(String[] a) throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of(a[0]));
    int reps = a.length > 1 ? Integer.parseInt(a[1]) : 3;

    long t0 = System.nanoTime();
    var module = Parser.parse(bytes);
    long parseMs = (System.nanoTime() - t0) / 1_000_000;

    long t1 = System.nanoTime();
    var inst = Instance.builder(module).build();
    long instMs = (System.nanoTime() - t1) / 1_000_000;

    var main = inst.export("main");
    long best = Long.MAX_VALUE;
    long code = -1;
    for (int i = 0; i < reps; i++) {
      long t = System.nanoTime();
      code = main.apply()[0];
      best = Math.min(best, System.nanoTime() - t);
    }
    System.out.printf("{\"parseMs\":%d,\"instantiateMs\":%d,\"bestMs\":%.1f,\"status\":%d}%n",
                      parseMs, instMs, best / 1e6, code);
  }
}
