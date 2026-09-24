package com.flint;

import com.flint.rt.Sandbox;

/// THE JVM'S ANSWERS TO `test/four-ops/contract.edn`, as `key<TAB>arg<TAB>value` lines.
///
/// One harness per target, one file of questions, one checker (`bin/check-four-ops`).
/// The thing being guarded against is not a target answering wrongly -- that shows
/// up at once -- it is two targets answering DIFFERENT QUESTIONS and both passing,
/// so the questions live in the shared file and the answers come back in a format
/// any language can print.
///
/// THE OUTPUT FORMAT IS THE CONTRACT BETWEEN HARNESSES. A CLR or wasm harness prints
/// the same keys:
///
/// <pre>
///   ops    names      the operations the artifact exposes, comma-separated
///   meta   &lt;name&gt;     the container's metadata under that name, read WITHOUT a call
///   link   before     accepted | refused
///   link   after      accepted | refused
///   boot   missing    the failure's text when a declared native is absent
///   boot   ok         clean, once the native is back
///   loop   at-rest    the status with nothing queued
///   loop   after-call the status once a call has been served
///   answer printable  what came back on the caller port
/// </pre>
///
/// `FourOps` FOR THREE OPERATIONS, and the name is kept on purpose. The record is
/// `DECISIONS.md#four-operations`, the gate is `bin/check-four-ops` and the case is
/// `test/four-ops/contract.edn` -- a reader following that chain should not lose it
/// at the last link because `prop` was dropped and one file was renamed. The record
/// kept its own slug for the same reason.
///
/// It goes through `flint.Artifact` REFLECTIVELY and touches nothing in
/// `com.flint.rt` but the three operations, `Bridge` and `Builtins.byName`. That
/// restriction is the test: this file is one package outside the runtime, so `javac`
/// refuses anything the contract does not offer.
public final class FourOps {
  static Class<?> k;
  static java.lang.reflect.Method boot, loop, link;

  static void say(String a, String b, String v) {
    // ONE LINE PER ANSWER, so a value containing a newline cannot be read as two
    // answers. The metadata is canonical EDN and has none, but asserting it here is
    // cheaper than discovering it from a checker that miscounted.
    System.out.println(a + "\t" + b + "\t" + (v == null ? "(absent)" : v.replace("\n", "\\n")));
  }

  /// The bridge, queueing outbound and letting the sandbox PULL.
  static final class Host extends Sandbox.Bridge {
    final java.util.Deque<Object[]> outbox = new java.util.ArrayDeque<>();
    String answer = null;
    Host() { super(1, "system"); }
    void queue(int port, byte[] w) { outbox.add(new Object[]{ port, w }); }
    protected Sandbox.Msg tryTake() {
      Object[] m = outbox.poll();
      return m == null ? null : new Sandbox.Msg((Integer) m[0], (byte[]) m[1]);
    }
    protected void put(int port, byte[] wire) {
      if (port == 2) answer = Main.printable(wire);
    }
  }

  static String cause(Exception e) {
    Throwable t = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }

  public static void main(String[] a) throws Exception {
    String entry = a.length > 0 ? a[0] : "t/main";
    String[] callArgs = new String[Math.max(0, a.length - 1)];
    System.arraycopy(a, 1, callArgs, 0, callArgs.length);

    k = Class.forName("flint.Artifact");
    boot = k.getMethod("boot", Sandbox.Bridge.class);
    loop = k.getMethod("loop");
    link = k.getMethod("link", String.class, com.flint.rt.Builtins.Fn.class);

    // WHICH OPERATIONS THE ARTIFACT ACTUALLY EXPOSES. Reported rather than assumed:
    // the drift to catch is a target keeping a fourth because its host found it
    // convenient, and a harness that only looked up the three it expected could not
    // see that.
    StringBuilder ops = new StringBuilder();
    for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
      if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
        if (ops.length() > 0) ops.append(',');
        ops.append(m.getName());
      }
    }
    say("ops", "names", ops.toString());

    // THE METADATA, FROM THE CONTAINER. Read off the class file's bytes with no call
    // into the artifact, which is the whole point of moving it out of `prop`. The
    // checker parses the EDN; nothing here does.
    String path = k.getName().replace('.', '/') + ".class";
    byte[] bytes;
    try (java.io.InputStream in = k.getClassLoader().getResourceAsStream(path)) {
      bytes = in.readAllBytes();
    }
    say("meta", "com.3sln.flint.meta", ClassAttr.read(bytes, "com.3sln.flint.meta"));
    // AND NOT under the un-namespaced name it used to have. JVMS 4.7's silent-ignore
    // rule makes a wrong attribute name quiet, so this is asked rather than assumed.
    say("meta", "flint", ClassAttr.read(bytes, "flint"));

    // LINK BEFORE BOOT: accepted. A null `fn` declares the native ABSENT, which is
    // how the missing-native rule gets exercised through the contract's own surface.
    try {
      link.invoke(null, "flint/str2", (com.flint.rt.Builtins.Fn) null);
      say("link", "before", "accepted");
    } catch (Exception e) {
      say("link", "before", "refused");
    }

    // BOOT MUST FAIL, NAMING IT. The deliberate change away from `Img.load`'s
    // null-tolerance: the version says what has to be linked, so a gap is a version
    // mismatch and is refused now rather than when something reaches it.
    try {
      boot.invoke(null, new Host());
      say("boot", "missing", "BOOTED ANYWAY");
    } catch (Exception e) {
      say("boot", "missing", cause(e));
    }

    // PUT IT BACK, from the host's own table, and boot for real.
    //
    // IN A TRY, because this line is only reachable when the boot above did what it
    // was supposed to. A runtime that BOOTED ANYWAY leaves `link` refusing, and an
    // unguarded call here killed the harness -- so the gate failed, correctly, with a
    // stack trace instead of the sentence naming the defect.
    try { link.invoke(null, "flint/str2", com.flint.rt.Builtins.byName("flint/str2")); }
    catch (Exception e) { say("link", "restore", cause(e)); }
    Host host = new Host();
    try {
      boot.invoke(null, host);
      say("boot", "ok", "booted");
    } catch (Exception e) {
      say("boot", "ok", cause(e));
    }

    // LINK AFTER BOOT: refused. Natives resolve exactly once when the image loads.
    try {
      link.invoke(null, "flint/add", (com.flint.rt.Builtins.Fn) (rt, at, argc) -> 0L);
      say("link", "after", "accepted");
    } catch (Exception e) {
      say("link", "after", "refused");
    }

    // The resting state, BEFORE any work is queued: a sandbox whose control plane is
    // parked on its system port answers NeedsHost and nothing else.
    say("loop", "at-rest", String.valueOf((Integer) loop.invoke(null)));

    host.queue(1, new Main.W().map(2).kw("op").kw("bind").kw("port").port(2).done());
    Main.W w = new Main.W().map(4).kw("tx").num(1).kw("op").kw("call").kw("fn").str(entry)
                           .kw("args").vec(callArgs.length);
    for (String s : callArgs) w.str(s);
    host.queue(2, w.done());

    int code = -1;
    for (int turn = 0; turn < 64 && host.answer == null; turn++) {
      code = (Integer) loop.invoke(null);
      if (code != Sandbox.NEEDS_HOST) break;
    }
    say("loop", "after-call", String.valueOf(code));
    say("answer", "printable", host.answer);
  }
}
