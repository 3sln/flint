package com.flint;

import com.flint.rt.Sandbox;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/// A HOST FOR A JVM ARTIFACT: `java -cp flint-rt.jar:. com.flint.Main <ns/fn> [args]`.
///
/// The artifact carries the program and NOT the runtime, so this side of the
/// classpath is what makes one runnable. It is also the `Main-Class` of the
/// convenience jar `bin/build-jvm-artifact :out` writes, which is those two things
/// in one file.
///
/// IT IS IN `com.flint`, NOT `com.flint.rt`, and that is the test. A consumer of
/// an artifact sees only public members of the runtime package, so putting this one
/// package out means the compiler refuses anything this file reaches for that the
/// three operations do not offer. `runtimes/jvm/test/HostCall.java` reaches into
/// `Conc.installSystemPort` and `Conc.drive` directly and proves the RUNTIME works;
/// only this proves the CONTRACT does.
///
/// It is also why this file carries a wire writer. The three operations take BYTES
/// on a bridge; the encoder belongs in an SDK beside `sdks/esm/src/codec.js`, and
/// there is no JVM SDK yet. Written by hand, tag for tag, exactly as `HostCall`
/// does and for the same reason: the bind message has to carry a port the guest
/// has not been given.
public final class Main {
  static final int SYSTEM = 1, CALLS = 2;
  static final int K_INT = 3, K_STRING = 5, K_KEYWORD = 6, K_VECTOR = 8,
                   K_MAP = 10, K_PORT = 15;
  static final long NO_NS = 0xffffffffL;

  /// Package-visible so `FourOps` writes the same wire bytes rather than a second
  /// copy of the encoder. There is no JVM SDK yet; when there is, both lose this.
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

  /// ONE BRIDGE FOR EVERY PORT, which is what `(port, bytes)` means.
  ///
  /// The host QUEUES what it wants to send and the sandbox pulls it, so nothing
  /// here needs a handle on the sandbox to deliver: a caller port is introduced by
  /// queueing on it, with no attach step and no fifth operation.
  static final class Host extends Sandbox.Bridge {
    record Out(int port, byte[] bytes) {}
    final Deque<Out> outbox = new ArrayDeque<>();
    String answer = null;

    Host() { super(SYSTEM, "system"); }

    void queue(int port, byte[] wire) { outbox.add(new Out(port, wire)); }

    protected Sandbox.Msg tryTake() {
      Out m = outbox.poll();
      return m == null ? null : new Sandbox.Msg(m.port(), m.bytes());
    }

    /// Not decoded, for the reason `HostCall` gives: a harness wants to SEE the
    /// `:return` and its value, and a second decoder next to the codec is the
    /// thing to avoid.
    protected void put(int port, byte[] wire) {
      if (port == CALLS) answer = printable(wire);
      else System.out.println("port " + port + " <- " + printable(wire));
    }

    protected void closed(int port) { System.out.println("port " + port + " closed"); }
  }

  /// THE GENERATED FACE, reached reflectively.
  ///
  /// `flint.Artifact` is emitted by `src/flint/jvm.cljc` at compile time, so this
  /// file cannot name it -- it is not on the classpath when this file is compiled,
  /// which is exactly the relationship a runtime has to a program. A consumer who
  /// knows the artifact's name writes `flint.Artifact.boot(port)` and needs none of
  /// this.
  static final class Face {
    final java.lang.reflect.Method boot, loop, link;
    final Class<?> clazz;
    Face(Class<?> k) throws Exception {
      clazz = k;
      boot = k.getMethod("boot", Sandbox.Bridge.class);            // -> Sandbox
      loop = k.getMethod("loop");                                  // -> int status
      link = k.getMethod("link", String.class, com.flint.rt.Builtins.Fn.class);
    }
    /// NO FALLBACK. The bytecode is constant data on that class, so a classpath
    /// without it is the runtime and no program.
    static Face find() {
      try { return new Face(Class.forName("flint.Artifact")); }
      catch (ClassNotFoundException e) {
        throw new IllegalStateException(
          "no flint.Artifact on the classpath: that class IS the compiled program, and"
          + " `:to :jvm` emits it. What is here is the flint runtime.");
      }
      catch (Exception e) { throw new IllegalStateException(e); }
    }
  }

  /// The artifact's metadata, out of the class ATTRIBUTE rather than a call.
  ///
  /// `prop` is gone (`DECISIONS.md#four-operations`) and this is what replaced it:
  /// the attribute is read from the class file's BYTES, so a reader needs no JVM, no
  /// class loading and nothing of flint's on its path. This host has the class
  /// loaded anyway, so it takes the bytes back off the class loader -- a build tool
  /// or `flint inspect` would read the file directly and get the same string.
  ///
  /// Walking to the class-level attributes means stepping over the constant pool,
  /// and that is the whole reason this is twenty lines rather than one: an
  /// annotation would be `clazz.getAnnotation(...)`. It would also need the
  /// annotation type on the classpath and the class loaded, which is the thing the
  /// record moved metadata AWAY from.
  static String metaAttribute(Face f) throws Exception {
    String path = f.clazz.getName().replace('.', '/') + ".class";
    byte[] b;
    try (java.io.InputStream in = f.clazz.getClassLoader().getResourceAsStream(path)) {
      if (in == null) return null;
      b = in.readAllBytes();
    }
    return ClassAttr.read(b, "com.3sln.flint.meta");
  }

  static com.flint.rt.Builtins.Fn constant(String s) {
    return (rt, at, argc) -> com.flint.rt.Str.of(rt, s);
  }

  public static void main(String[] a) throws Exception {
    String fn = a.length > 0 ? a[0] : "t/main";
    String[] args = new String[Math.max(0, a.length - 1)];
    System.arraycopy(a, 1, args, 0, args.length);
    String hook = System.getenv("FLINT_LINK");

    Face face = Face.find();

    // --- The metadata, from the container and not from a call. Read BEFORE
    //     anything runs, which is the point: a runner decides whether to load at
    //     all from this, and a method could not have answered it.
    System.out.println("meta: " + metaAttribute(face));

    // --- link, BEFORE boot, because natives resolve exactly once at load. ZERO
    //     calls is the normal case; this runs only when asked for one.
    if (hook != null) {
      face.link.invoke(null, hook, constant("LINKED"));
      System.out.println("link " + hook + " registered before boot");
    }

    // --- boot: ONE bridge, which becomes the system port.
    Host host = new Host();
    face.boot.invoke(null, host);
    System.out.println("boot: ok");

    // --- link AFTER boot must be refused. Shown rather than asserted in a comment:
    //     an override registered now would silently never apply.
    try {
      face.link.invoke(null, "flint/add", constant("TOO LATE"));
      System.out.println("link after boot: ACCEPTED -- the contract says it must not be");
    } catch (java.lang.reflect.InvocationTargetException e) {
      System.out.println("link after boot: refused (" + e.getCause().getClass().getSimpleName() + ")");
    }

    // --- bind a caller, then call. Queued in that order on purpose: a port queues,
    //     so the call waits behind the bind for the thread the bind creates.
    host.queue(SYSTEM, new W().map(2).kw("op").kw("bind").kw("port").port(CALLS).done());
    W w = new W().map(4).kw("tx").num(1).kw("op").kw("call").kw("fn").str(fn)
                 .kw("args").vec(args.length);
    for (String s : args) w.str(s);
    host.queue(CALLS, w.done());

    int code = Sandbox.NEEDS_HOST;
    for (int turn = 0; turn < 64 && host.answer == null; turn++) {
      code = (Integer) face.loop.invoke(null);
      if (code != Sandbox.NEEDS_HOST) break;
    }
    System.out.println("loop -> " + code + " ("
                       + (code == Sandbox.DONE ? "Done"
                          : code == Sandbox.THREW ? "Threw" : "NeedsHost, the resting state")
                       + ")");
    System.out.println("answer: " + host.answer);
    System.exit(host.answer == null ? 1 : 0);
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
