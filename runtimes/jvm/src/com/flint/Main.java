package com.flint;

import com.flint.rt.Sandbox;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// THE RUNNER INSIDE THE ARTIFACT: `java -jar app.jar <ns/fn> [args]`.
///
/// It is the `Main-Class` of every `:to :jvm` artifact, which is what makes one
/// runnable rather than merely loadable -- a compiled artifact you cannot run is
/// a library, and `:to :wasm` produces something `flint run` executes.
///
/// IT IS IN `com.flint`, NOT `com.flint.rt`, and that is the test. A consumer of
/// an artifact sees only public members of the runtime package, so putting this
/// one package out means the compiler refuses anything this file reaches for that
/// the four operations do not offer. `runtimes/jvm/test/HostCall.java` reaches
/// into `Conc.installSystemPort` and `Conc.drive` directly and proves the
/// runtime works; only this proves the CONTRACT does.
///
/// It touches `Sandbox.boot`, `Sandbox.loop`, `Sandbox.link` and `Sandbox.prop`
/// and NOTHING else in `com.flint.rt` -- no `Rt`, no `Img`, no `Conc`. That
/// restriction is the test: `runtimes/jvm/test/HostCall.java` reaches into
/// `Conc.installSystemPort` and `Conc.drive` directly, which a consumer of an
/// artifact cannot do, so it proves the runtime works and not that the artifact
/// contract does.
///
/// It is also the reason this class carries a wire writer. The four operations
/// take BYTES on a bridge; the encoder belongs in an SDK beside
/// `sdks/esm/src/codec.js`, and there is no JVM SDK yet. Written by hand here,
/// tag for tag, exactly as `HostCall` does and for the same reason: the bind
/// message has to carry a port the guest has not been given.
public final class Main {
  static final int SYSTEM = 1, CALLS = 2;
  static final int K_INT = 3, K_STRING = 5, K_KEYWORD = 6, K_VECTOR = 8,
                   K_MAP = 10, K_PORT = 15;
  static final long NO_NS = 0xffffffffL;

  static final class W {
    final ByteArrayOutputStream b = new ByteArrayOutputStream();
    W tag(int x) { b.write(x); return this; }
    W u32(long v) { for (int i = 0; i < 4; i++) b.write((int) ((v >>> (8 * i)) & 0xff)); return this; }
    W i64(long v) { for (int i = 0; i < 8; i++) b.write((int) ((v >>> (8 * i)) & 0xff)); return this; }
    W text(String s) {
      byte[] u = s.getBytes(StandardCharsets.UTF_8);
      u32(u.length); b.write(u, 0, u.length); return this;
    }
    W kw(String n) { return tag(K_KEYWORD).u32(NO_NS).text(n); }
    W str(String s) { return tag(K_STRING).text(s); }
    W num(long n) { return tag(K_INT).i64(n); }
    W port(int id) { return tag(K_PORT).u32(id); }
    W map(int n) { return tag(K_MAP).u32(n); }
    W vec(int n) { return tag(K_VECTOR).u32(n); }
    byte[] done() { return b.toByteArray(); }
  }

  /// Whatever came back on the call port, rendered printable. Not decoded, for
  /// the reason `HostCall` gives: a harness wants to SEE the `:return` and its
  /// value, and a second decoder next to the codec is the thing to avoid.
  static String answer = null;

  static final class Sys extends Sandbox.Bridge {
    Sys() { super(SYSTEM, "system"); }
    protected void message(byte[] wire) { System.out.println("system <- " + printable(wire)); }
  }

  static final class Calls extends Sandbox.Bridge {
    Calls() { super(CALLS, "calls"); }
    protected void message(byte[] wire) { answer = printable(wire); }
  }

  /// THE GENERATED FACE, reached reflectively.
  ///
  /// `flint.Artifact` is emitted by `src/flint/jvm.cljc` at compile time, so this
  /// file cannot name it -- it is not on the classpath when this file is
  /// compiled, which is exactly the relationship a runtime has to a program. That
  /// is also why reflection is the honest way to check it: a consumer who knows
  /// the artifact's name writes `flint.Artifact.boot(port)` directly and needs
  /// none of this.
  static final class Face {
    final java.lang.reflect.Method boot, loop, link, prop;
    Face(Class<?> k) throws Exception {
      boot = k.getMethod("boot", Sandbox.Bridge.class, Sandbox.Natives.class);  // -> Sandbox
      loop = k.getMethod("loop");
      link = k.getMethod("link", String.class, com.flint.rt.Builtins.Fn.class);
      prop = k.getMethod("prop", String.class, byte[].class);
    }
    /// NO FALLBACK. The image is constant data on this class, so a jar without it
    /// is the interpreter and no program -- there is nothing to fall back to, and
    /// an earlier version of this file booted `com.flint.rt.Sandbox` directly,
    /// which was possible only while the image was a resource.
    static Face find() throws Exception {
      try { return new Face(Class.forName("flint.Artifact")); }
      catch (ClassNotFoundException e) {
        throw new IllegalStateException(
          "this jar carries no flint.Artifact: it is the flint runtime, not a compiled"
          + " program. `:to :jvm` emits that class; see src/flint/jvm.cljc.");
      }
    }
  }

  public static void main(String[] a) throws Exception {
    String fn = a.length > 0 ? a[0] : "t/main";
    String[] args = new String[Math.max(0, a.length - 1)];
    System.arraycopy(a, 1, args, 0, args.length);
    // A hook the CALLER names, so the same runner can prove `link` on whatever
    // builtin the program it was handed actually imports.
    String hook = System.getenv("FLINT_LINK");

    Face face = Face.find();
    System.out.println("face: flint.Artifact, generated, carrying the image as constant data");

    // --- prop, BEFORE anything runs, and THROUGH THE GENERATED CLASS. A runner
    //     decides whether to load at all from what the artifact says about
    //     itself, so this has to answer with no sandbox alive.
    for (String k : new String[]{ "version", "compat.abi", "compat.key", "target", "image.bytes" }) {
      byte[] buf = new byte[256];
      int n = (Integer) face.prop.invoke(null, k, buf);
      System.out.println("prop " + k + " = " + (n < 0 ? "(absent)" : new String(buf, 0, Math.min(n, buf.length))));
    }

    // --- prop with a SHORT buffer: the length comes back anyway, which is what
    //     lets a caller ask again. A pointer ABI has no other way to say it.
    byte[] small = new byte[4];
    int need = (Integer) face.prop.invoke(null, "compat.abi", small);
    System.out.println("prop compat.abi needs " + need + " bytes, buffer held " + small.length
                       + " -> \"" + new String(small, 0, Math.min(need, small.length)) + "\" (truncated)");
    System.out.println("prop no.such.prop -> " + face.prop.invoke(null, "no.such.prop", small));

    // --- boot: the bridge that becomes the system port.
    Sys sys = new Sys();
    // THE RUNTIME, PLUGGED IN, as one method reference. The artifact carries the
    // program's code and none of the interpreter, and its image declares 88
    // builtins by name for a one-line program -- so this is the whole of what
    // would otherwise be 88 `link` calls the host writes as a loop.
    Sandbox box = (Sandbox) face.boot.invoke(null, sys, (Sandbox.Natives) com.flint.rt.Builtins::byName);
    System.out.println("boot: " + box.resolved() + " of " + box.declared()
                       + " declared builtins resolved by the host");

    // --- link: a hook, by name, on an ALREADY-LOADED image. A count of 0 means
    //     the program never imports that name, which is worth reporting and is
    //     not an error -- a hook nothing calls cannot be reached whatever we do.
    com.flint.rt.Builtins.Fn linked = (rt, at, argc) -> com.flint.rt.Str.of(rt, "LINKED");
    // `FLINT_LINK=-name` UNBINDS instead, which is the adversarial half: a host
    // whose resolver answers for fewer names than the image declares must fail by
    // NAMING the builtin it reached, not by computing something plausible.
    if (hook != null) {
      boolean drop = hook.startsWith("-");
      String name = drop ? hook.substring(1) : hook;
      int bound = (Integer) face.link.invoke(null, name, drop ? null : linked);
      System.out.println("link " + name + (drop ? " UNBOUND " : " bound ") + bound + " slot(s)");
    }
    System.out.println("link no/such/builtin bound "
                       + face.link.invoke(null, "no/such/builtin", linked) + " slot(s)");

    // --- bind a caller, then call. Queued in that order on purpose: a port
    //     queues, so the call waits behind the bind for the thread it creates,
    //     and nothing is pumped between the two.
    Calls calls = new Calls();
    calls.attach(box);
    sys.send(new W().map(2).kw("op").kw("bind").kw("port").port(calls.id).done());
    W w = new W().map(4).kw("tx").num(1).kw("op").kw("call").kw("fn").str(fn)
                 .kw("args").vec(args.length);
    for (String s : args) w.str(s);
    calls.send(w.done());

    int code = Sandbox.NEEDS_HOST;
    for (int turn = 0; turn < 64 && answer == null; turn++) {
      code = (Integer) face.loop.invoke(null);
      if (code == Sandbox.SETTLED) break;
    }
    System.out.println("loop -> " + code);
    System.out.println("answer: " + answer);
    System.exit(answer == null ? 1 : 0);
  }

  static String printable(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) {
      int c = x & 0xff;
      sb.append(c >= 32 && c < 127 ? (char) c : '.');
    }
    return sb.toString();
  }
}
