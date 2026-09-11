import com.flint.rt.*;
import java.nio.file.*;

/// The flint COMPILER, on the ported runtime.
///
/// The difference between "runs programs" and "self-hosts". The compiler is the
/// largest flint program there is -- 1,381 functions, 2,505 constants, 144
/// builtins -- so it reaches parts a small program never does, and it is the
/// only thing that says whether a port carries the whole language rather than
/// the part the tests happened to use.
///
/// Four bugs came out of it that ten conformance programs did not find:
///
///  * `array-map` read as varargs, so every map LITERAL was empty;
///  * a tail call to a non-closure entered it as a closure, reading a slot off
///    a fixnum -- an address 2 TB into a 2 GB heap;
///  * `=` between different kinds threw instead of answering false;
///  * symbols compared by IDENTITY, so every local the analyser looked up
///    through a metadata-carrying symbol came back unbound.
///
/// It is checked against the wasm compiler's OUTPUT, byte for byte. "It ran"
/// would have passed with the map literals empty.
public class RtSelfHost {
  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(64L * 1024 * 1024, 2048L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of("dist/flintc.bytecode")));
    if (img == null) { System.out.println("  FAIL not an image"); System.exit(1); }
    System.out.println("  the compiler: " + rt.fns.length + " fns, " + rt.consts.length
                       + " consts, " + rt.code.length + " code bytes, "
                       + img.nativeNames.length + " natives");
    int missing = 0;
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < img.nativeNames.length; i++) {
      if (rt.natives[i] == null) { missing++; if (missing <= 60) names.append(" ").append(img.nativeNames[i]); }
    }
    System.out.println("  builtins it wants that this runtime lacks: " + missing + names);
    for (int fn : img.init) rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
    System.out.println("  ok   " + img.init.length + " initialisers ran");
    // ROOTED. `makeClosure` allocates, allocating can collect, and the nursery
    // is a copying collector -- so a spec held in a Java local across it comes
    // back holding the address the string had BEFORE the flip. Java evaluates
    // arguments left to right, so `makeClosure` runs first and `spec` is read
    // after it. That is `DECISIONS.md#a-vec-of-values-is-not-a-root`, in the test harness rather than
    // in the runtime, and it read back as the compiler's reader failing at
    // column 2 of its input.
    // `flint.selfhost/main` is a VAR, not a named entry in the function table
    // -- functions there are named `main`, `-main` and so on, while the
    // qualified name lives in the var table. The initialisers put the closure
    // in its slot, which is why they have to have run first.
    //
    // `img.entry` is a DIFFERENT function, and calling it handed the compiler
    // something that was not the spec: the reader then failed at column 2 of
    // whatever it got, which read exactly like a runtime bug in the reader.
    int slot = -1;
    for (int i = 0; i < img.varNames.length; i++) {
      if ("flint.selfhost/main".equals(Str.text(rt, rt.consts[img.varNames[i]]))) slot = i;
    }
    if (slot < 0) { System.out.println("  FAIL flint.selfhost/main is not in the var table"); System.exit(1); }
    long compiler = rt.roots.shared.globals[slot];
    if (Val.isNil(compiler)) {
      System.out.println("  FAIL flint.selfhost/main is unbound after the initialisers");
      System.exit(1);
    }
    System.out.println("  ok   flint.selfhost/main is bound: " + rt.describe(compiler));
    // ROOTED. `Str.of` on a 97 KB spec allocates, and so does anything after
    // it: a value held in a Java local across an allocation comes back holding
    // the address it had before the collector moved it. `DECISIONS.md#a-vec-of-values-is-not-a-root`.
    int sbase = rt.mark();
    int ci = rt.push(compiler);
    int si = rt.push(Str.of(rt, new String(Files.readAllBytes(Path.of(a[0])),
                                           java.nio.charset.StandardCharsets.UTF_8)));
    // A LIST CONTAINING the spec, not the spec. `selfhost/main` takes an
    // argv and dispatches on its FIRST element, so handing it the string bare
    // made `(first spec)` the one-character string `{` -- and the reader then
    // failed at column 2 of a one-character input, which read exactly like a
    // reader bug on a 97 KB file.
    int li = rt.push(Seqs.fromRoots(rt, si, 1));
    long out = rt.runProgram(rt.r(ci), new long[]{ rt.r(li) });
    rt.popTo(sbase);
    String s = Str.isString(rt, out) ? Str.text(rt, out) : "NOT A STRING: " + rt.describe(out);
    System.out.println("  ok   the compiler ran and produced " + s.length() + " chars");
    if (!Val.isNil(rt.thrown)) {
      System.out.println("  FAIL the compiler threw: " + rt.describe(rt.thrown)
        + (rt.isException(rt.thrown) && Str.isString(rt, rt.exMessage(rt.thrown))
           ? " :: " + Str.text(rt, rt.exMessage(rt.thrown)) : ""));
      System.exit(1);
    }
    // Against the REFERENCE, byte for byte. A compiler that runs and emits
    // something is not a compiler that works.
    String want = new String(Files.readAllBytes(Path.of(a[1])),
                             java.nio.charset.StandardCharsets.UTF_8);
    if (!s.equals(want)) {
      System.out.println("  FAIL the output differs from the wasm compiler's ("
                         + s.length() + " vs " + want.length() + " chars)");
      System.exit(1);
    }
    System.out.println("  ok   and it is byte for byte what the wasm compiler emits");
  }
}
