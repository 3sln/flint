import com.flint.rt.*;
import java.nio.file.*;

/// The catchable memory-limit error (`DECISIONS.md#resource-limits`), on a
/// REAL compiled image -- `runtimes/conform/oom.cljc` -- called the same way
/// `test/limits.mjs` calls the wasm runtime: `HostCall.call`, a bridge call
/// naming `oom/main` directly, not the compiler's CLI shim
/// (`flint.main/-main`), so the allocation sequence this exercises is the
/// SAME one a bridge call on any other runtime exercises and the numbers in
/// the error are comparable byte for byte. A 2 MiB nursery and a 6 MiB
/// ceiling, matching `Rt::new` plus `set_memory_limit(6 MiB)` -- the two are
/// the same thing, since nothing has allocated yet when that call happens.
///
/// Proves the gap `ROADMAP.md` recorded 2026-10-05 is closed: before
/// `Rt.oomUnwind` existed, `oom/main ["eat"]` here answered a bare `nil`
/// (`a == 0` read as `Val.NIL`, the exact wrong-answer bug native's own
/// `alloc` comment warns about) instead of raising. Run directly --
/// `bin/conform-hosts` is not touched by this file. The CLR's sibling is
/// `Conform --rt-oom`, and native's is `cli/src/main.rs`'s `oom_tests`.
///
///     ./bin/flint :src runtimes/conform :fn oom/main --emit-image \
///       :out out/conform/oom.img
///     java -cp runtimes/jvm/classes RtOom out/conform/oom.img eat
public class RtOom {
  public static void main(String[] a) throws Exception {
    if (a.length < 2) {
      System.err.println("usage: RtOom <image> <eat|work>");
      System.exit(2);
    }
    Rt rt = new Rt(2L * 1024 * 1024, 6L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("FAIL not a flint image"); System.exit(1); }
    HostCall.call(rt, "oom/main", new String[]{ a[1] });
    System.out.println(HostCall.lastAnswer);
  }
}
