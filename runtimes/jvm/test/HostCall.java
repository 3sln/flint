import com.flint.rt.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// Call a named function the way a HOST does: over bridge ports.
///
/// The ports had no such path. Their harnesses ran `img.entry` through
/// `runProgram`, which is the model that existed before a sandbox became a
/// thing you CALL (`DECISIONS.md#bridges-are-the-only-door`), and it was the
/// last way into a sandbox that was not a message on a port.
///
/// It is not only tidiness. That difference is MEASURABLE: a call over a bridge
/// runs as a green thread, so it is preempted every `SLICE` and pays for each
/// resume, and `runProgram` installs no scheduler and never pays. About 48
/// steps per 4096-step slice, which is exactly the gap `bin/conform-hosts`
/// reports on its gas row -- see the note there.
///
/// The wire format is `DECISIONS.md#structured-ports`, written by hand because
/// the bind message has to carry a port the guest has NOT been given yet.
/// Installing it first is a race: `drive` begins by reaping, so a port the host
/// installed but the guest has not referenced is collected before the bind that
/// would reference it is served. `sdks/esm/src/codec.js` is the reference for
/// every tag here.
public final class HostCall {
  public static final int SYSTEM = 1, CALLS = 2;

  /// The last answer's bytes, rendered printable.
  ///
  /// Not decoded: a harness using this wants to SEE what came back -- a
  /// `:return` and its value, or a `:throw` and the message and frame chain
  /// flint built -- and the wire format is readable enough for that. Decoding
  /// it properly would mean a second decoder next to `Codec`, which is the
  /// thing this file is trying not to be.
  public static String lastAnswer = "(none)";

  // --- the writer, matching sdks/esm/src/codec.js tag for tag ---------------
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
    /// An unqualified keyword: absent namespace, then the name.
    W kw(String n) { return tag(K_KEYWORD).u32(NO_NS).text(n); }
    W str(String s) { return tag(K_STRING).text(s); }
    W num(long n) { return tag(K_INT).i64(n); }
    W port(int id) { return tag(K_PORT).u32(id); }
    W map(int n) { return tag(K_MAP).u32(n); }
    W vec(int n) { return tag(K_VECTOR).u32(n); }
    byte[] done() { return b.toByteArray(); }
  }

  /// Give this sandbox its door and bind a port to call on.
  ///
  /// A CALLER IS THE THING YOU CALL ON, not the sandbox: `:bind` spawns ONE
  /// thread serving calls on the port, so a caller is that thread's queue, and
  /// concurrency is had by taking a second one. Named the same way here as in
  /// `sdks/esm/src/guest.js` and `cli/src/serve.rs`, because it is the same
  /// protocol and there is no reason for three names for it.
  public static void caller(Rt rt, int port) {
    Conc.installSystemPort(rt, SYSTEM, Str.of(rt, "system"));
    Conc.hostDeliver(rt, SYSTEM, new W().map(2)
        .kw("op").kw("bind")
        .kw("port").port(port)
        .done());
  }

  /// Bind the default caller and run `fn` on it.
  ///
  /// Returns the status `drive` last reported. The answer is not decoded: what
  /// the callers of this need is that the call RAN, and on the same terms every
  /// other runtime runs one.
  public static long call(Rt rt, String fn, String[] args) {
    caller(rt, CALLS);
    // NOT DRIVEN BETWEEN THE TWO. A port queues, so the call waits behind the
    // bind for the thread the bind creates.
    W w = new W().map(4)
        .kw("tx").num(1)
        .kw("op").kw("call")
        .kw("fn").str(fn)
        .kw("args").vec(args.length);
    for (String a : args) w.str(a);
    Conc.hostDeliver(rt, CALLS, w.done());

    // PUMPED UNTIL THE ANSWER, not until the sandbox is idle: the control plane
    // is parked on the system port, so "needs the host" is where it RESTS.
    long code = Conc.drive(rt);
    for (int guard = 0; guard < 1000; guard++) {
      if (answered(rt)) break;
      if (code != 2) break;
      code = Conc.drive(rt);
    }
    return code;
  }

  /// Has an answer come back on the call port?
  ///
  /// The records are five little-endian `u32`s -- `kind, a, b, off, len` --
  /// which is the same layout every host reads (`DECISIONS.md#host-abi`).
  /// Kind 2 is a message and `a` is the port it arrived on.
  static boolean answered(Rt rt) {
    Conc.Events evs = Conc.drainEvents(rt);
    for (int i = 0; i < evs.count(); i++) {
      if (word(evs.bytes(), i * 20) == 2 && word(evs.bytes(), i * 20 + 4) == CALLS) {
        int off = (int) word(evs.bytes(), i * 20 + 12);
        int len = (int) word(evs.bytes(), i * 20 + 16);
        StringBuilder sb = new StringBuilder();
        for (int k = off; k < off + len && k < evs.bytes().length; k++) {
          int c = evs.bytes()[k] & 0xff;
          sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        lastAnswer = sb.toString();
        return true;
      }
    }
    return false;
  }

  static long word(byte[] b, int at) {
    long v = 0;
    for (int i = 3; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xffL);
    return v;
  }
}
