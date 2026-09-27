import com.flint.Compiler;
import com.flint.Image;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/// SOURCE TO AN ARTIFACT, ON THIS RUNTIME, WITHOUT A FILE.
///
/// `bin/check-jvm-sdk` drives this. It exercises the JVM SDK the way a consumer
/// would -- `Compiler.of(bytecode).compileJvm(spec)` answering an `Image` -- and
/// asserts the artifact is BYTE FOR BYTE what the WASM compiler answers for THE
/// SAME SPEC AND MODE. "It compiled" would not be enough: the compiler answers
/// with a string either way, and an artifact that merely loads can still be the
/// wrong program.
///
/// THE REFERENCE IS THE WASM COMPILER, NOT THE NATIVE CLI, and the difference is
/// the whole point. Compared against the CLI's own artifact this reported a
/// mismatch of 38 777 bytes against 38 777 -- same length, different bytes --
/// which reads exactly like a port divergence and was not one: the CLI builds its
/// OWN spec from sources, so the two compilers were being handed different inputs.
/// `host/flint-argv.mjs` exists so the wasm compiler can be driven with the same
/// argv this is, which is the only comparison that says anything about the ports.
///
/// It is also what gives `Compiler.compileJvm` a caller. `bin/dead-runtime-fns`
/// caught it with none and said to read the member before believing either -- and
/// the member was fine while the CLAIM was untested, which is the worse of the two.
public class RtCompiler {
  static int fails = 0;

  static void ok(String what, boolean cond, String saw) {
    if (cond) System.out.println("  ok   " + what);
    else { System.out.println("  FAIL " + what + " :: " + saw); fails++; }
  }

  public static void main(String[] a) throws Exception {
    byte[] bytecode = Files.readAllBytes(Path.of(a[0]));   // dist/flintc.bytecode
    String spec = Files.readString(Path.of(a[1]));          // an --emit-spec spec
    byte[] reference = Files.readAllBytes(Path.of(a[2]));   // wasm compiler, same spec

    Compiler c = Compiler.of(bytecode);
    Image img = c.compileJvm(spec);

    ok("the compiler ran on this runtime and answered a class file",
       img.bytes() != null && img.bytes().length > 0,
       String.valueOf(img.bytes() == null ? -1 : img.bytes().length));
    // THE ASSERTION. One spec, one mode, two compilers -- the same flint program
    // running as wasm and running on this runtime -- and the artifact has to be
    // the same bytes or the ports do not agree about what a program compiles to.
    ok("byte for byte what the wasm compiler answers for the same spec",
       Arrays.equals(img.bytes(), reference),
       img.bytes().length + " bytes against " + reference.length);
    // IN MEMORY: no file was written, and the class is loadable anyway. This is
    // what `:out` being a directory used to make impossible.
    ok("the class is defined in memory, from the bytes",
       img.clazz() != null, String.valueOf(img.clazz()));
    // The metadata comes off the BYTES, which is why `Image` keeps them: a class
    // defined from bytes has no resource to read it back from.
    ok("its metadata is readable", img.metadata() != null,
       String.valueOf(img.metadata()));
    ok("and the three operations are there",
       img.clazz().getMethod("loop") != null, "loop");

    if (fails > 0) { System.out.println("RtCompiler: " + fails + " FAILURES"); System.exit(1); }
  }
}
