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

  // THE FACE AND THE METADATA READER USED TO LIVE HERE, privately, and
  // `FourOps` had its own copy of the first. Both are `com.flint.Image` now --
  // the JVM's mirror of the ESM SDK's `Image` -- so this harness drives the same
  // API a consumer does. It also fixes what the private reader could not: it
  // recovered the class bytes through `getResourceAsStream`, which returns NULL
  // for a class defined from bytes, so a byte-loaded artifact had metadata that
  // was in the file and unreachable. `Image` keeps the bytes.

  static com.flint.rt.Builtins.Fn constant(String s) {
    return (rt, at, argc) -> com.flint.rt.Str.of(rt, s);
  }

  public static void main(String[] a) throws Exception {
    String fn = a.length > 0 ? a[0] : "t/main";
    String[] args = new String[Math.max(0, a.length - 1)];
    System.arraycopy(a, 1, args, 0, args.length);
    String hook = System.getenv("FLINT_LINK");

    com.flint.Image face = com.flint.Image.onClasspath();

    // --- The metadata, from the container and not from a call. Read BEFORE
    //     anything runs, which is the point: a runner decides whether to load at
    //     all from this, and a method could not have answered it.
    System.out.println("meta: " + face.metadata());

    // --- link, BEFORE boot, because natives resolve exactly once at load. ZERO
    //     calls is the normal case; this runs only when asked for one.
    if (hook != null) {
      face.link(hook, constant("LINKED"));
      System.out.println("link " + hook + " registered before boot");
    }

    // --- boot: ONE bridge, which becomes the system port.
    Host host = new Host();
    face.boot(host);
    System.out.println("boot: ok");

    // --- link AFTER boot must be refused. Shown rather than asserted in a comment:
    //     an override registered now would silently never apply.
    try {
      face.link("flint/add", constant("TOO LATE"));
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
      code = face.loop();
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
