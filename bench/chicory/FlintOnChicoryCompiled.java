// Chicory in COMPILER mode: wasm translated to JVM bytecode rather than
// interpreted. Measured because reporting only the interpreter would be an
// unfair reading of what the JVM can do with a flint module.
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import java.nio.file.*;

public class FlintOnChicoryCompiled {
  public static void main(String[] a) throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of(a[0]));
    int reps = a.length > 1 ? Integer.parseInt(a[1]) : 3;
    long t0 = System.nanoTime();
    var module = Parser.parse(bytes);
    long parseMs = (System.nanoTime() - t0) / 1_000_000;
    long t1 = System.nanoTime();
    var inst = Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build();
    long instMs = (System.nanoTime() - t1) / 1_000_000;
    var main = inst.export("main");
    long best = Long.MAX_VALUE; long code = -1;
    for (int i = 0; i < reps; i++) {
      long t = System.nanoTime();
      code = main.apply()[0];
      best = Math.min(best, System.nanoTime() - t);
    }
    System.out.printf("{\"parseMs\":%d,\"compileMs\":%d,\"bestMs\":%.1f,\"status\":%d}%n",
                      parseMs, instMs, best / 1e6, code);
  }
}
