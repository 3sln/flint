package com.flint.rt;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// THE FOUR OPERATIONS AN ARTIFACT HAS, on the JVM.
///
/// `boot`, `loop`, `link`, `prop` -- and nothing else. Every target is meant to
/// expose these four and no more; this is the JVM face of them.
///
/// ## "Self-contained" means ALL OF THE PROGRAM'S CODE, not "runs standalone"
///
/// An artifact is one class file: the bytecode, the compiled arities when there
/// are any, and the four operations. It does NOT contain the interpreter, the
/// collector or the builtins -- the HOST carries those and plugs them in, the
/// builtins through the resolver `boot` takes (`Natives`) and an override through
/// `link`. So `java -cp flint-rt.jar:. ...` is the shape, and an artifact on its
/// own runs nothing.
///
/// That is the opposite of what this file said while the interpreter travelled in
/// the jar beside the image, and the difference is worth stating rather than
/// quietly correcting: a reader who takes "self-contained" to mean "needs nothing
/// else" will be wrong about the deployment.
///
/// ## The program is data ON A CLASS, not a file beside one
///
/// `boot` is handed the image as string constants and the properties as a string,
/// both of which live in the constant pool of `flint.Artifact` -- the class
/// `src/flint/jvm.cljc` emits for each program. There is NO `flint/program.image`
/// resource and nothing here opens one.
///
/// That is a deliberate reversal of the obvious design. A resource is fewer moving
/// parts and makes the bytecode a FILE the artifact contains; constant data makes
/// it part of what the artifact IS, which is the shape the CLR will use as well, so
/// the two targets stay uniform rather than each being natural on its own terms.
///
/// It also keeps the format an implementation detail in the way that matters:
/// nothing public says where the program came from, so an AOT packager emitting
/// compiled classes beside `flint.Artifact` moves no signature here.
///
/// The difference from `runtimes/jvm/test/RtSteps.java`, which reads a `.image`
/// from the command line, is the point: a consumer of an artifact has an artifact,
/// not a filesystem layout.
///
/// ## What the JVM does that a pointer ABI cannot
///
/// A bridge is ONE OBJECT with both directions on it. On wasm the host writes
/// into linear memory (`flint_in_alloc`) and then names a port id
/// (`flint_deliver`), drains with `flint_drain`, and reads five-`u32` records at
/// `flint_events_ptr` -- five ABI functions for what is here two methods on a
/// `Bridge`, because a JVM sandbox can hand the host a callable and a wasm
/// module cannot. The same reason removes `flint_grant`, `flint_answer` and
/// `flint_continue`: a host's answer to an `open` is this `Bridge`'s RETURN
/// VALUE, so there is no token to keep and no stale-token generation to check.
///
/// ## What it cannot do better
///
/// `prop(name, buf)` keeps the pointer shape -- a caller-supplied buffer and a
/// length back -- because the contract says four operations and `String
/// prop(String)` would be a fifth thing to keep in step. It is written the way
/// wasm will have to write it; see the note on the method.
public final class Sandbox {

    // --- the bridge --------------------------------------------------------

    /// A port the HOST owns, with both directions on it.
    ///
    /// `id` is the host's, and that is the inversion the whole port design
    /// rests on (`DECISIONS.md#ports-are-the-hosts`): the sandbox does not mint
    /// an endpoint and offer it up, so an id means the same thing in every
    /// sandbox holding it.
    ///
    /// Subclass it, implement `message`, and call `send`. `send` before `boot`
    /// throws rather than dropping the bytes -- a message delivered to nothing
    /// is the failure that reads as "the call was never answered".
    public abstract static class Bridge {
        public final int id;
        public final String label;
        private Sandbox box;

        public Bridge(int id, String label) { this.id = id; this.label = label; }

        /// Tell this bridge which sandbox it belongs to.
        ///
        /// NOT A FIFTH OPERATION. `boot` takes the system port and nothing
        /// else, exactly as the contract says; every other port the host owns is
        /// either granted through `open` -- which attaches it for you -- or
        /// declared here, on the HOST's own object, before the host sends on it.
        /// wasm cannot do this and needs a thirteenth ABI function
        /// (`flint_install_port`) for the same job, because there is no host
        /// object for a wasm module to hold onto.
        public final Bridge attach(Sandbox box) { box.wire(this); return this; }

        /// GUEST TO HOST. The bytes are the wire format
        /// (`DECISIONS.md#structured-ports`), and they are BYTES: a port's
        /// format may be binary, so decoding them as UTF-8 here would corrupt
        /// whatever is not valid.
        protected abstract void message(byte[] wire);

        /// The guest closed its end. Nothing more will arrive on this bridge.
        protected void closed() {}

        /// The guest called `open` on a capability. Return the bridge to grant
        /// it, or null to refuse -- a refusal is a normal outcome and surfaces
        /// in the program as a catchable error.
        ///
        /// A GRANT NAMES A PORT, which is why this returns one rather than a
        /// boolean: there is no port until the host says which one.
        protected Bridge open(String capability, byte[] args) { return null; }

        /// The guest asked the host for a value that is not a port
        /// (`DECISIONS.md#workspace-capabilities` step 7). Return the encoded
        /// value, or null to refuse.
        protected byte[] request(byte[] wire) { return null; }

        /// HOST TO GUEST. Enqueues and wakes; it never re-enters the scheduler,
        /// so it is safe from inside `message`. Call `loop` afterwards.
        public final void send(byte[] wire) {
            if (box == null) throw new IllegalStateException(
                "bridge " + id + " is not wired to a sandbox: call Sandbox.boot(bridge) first");
            box.pending = true;
            Conc.hostDeliver(box.rt, id, wire);
        }

        /// Close the host's end.
        public final void close() {
            if (box != null) Conc.hostClosePort(box.rt, id);
        }
    }

    // --- the runtime the host plugs in -------------------------------------

    /// HOW THE RUNTIME ARRIVES: one function from a builtin's name to its
    /// implementation.
    ///
    /// The artifact carries the program's code and not the runtime, so the
    /// builtins have to come from outside -- and the image declares them BY NAME:
    /// 88 of them for `(ns t) (defn main [args] "x")`, which is `clojure.core`'s
    /// reach rather than the program's, against 223 the runtime carries in all.
    ///
    /// EIGHTY-EIGHT `link` CALLS IS THE WRONG SHAPE, and not only because it is a
    /// loop the host writes by hand. The image's native table is fixed the moment
    /// the bytecode is read, so resolution has to happen DURING boot -- a host
    /// linking 88 names beforehand has no sandbox to link them into. A resolver is
    /// the only form that can be asked at the right time, and it makes the whole
    /// case one expression:
    ///
    /// <pre>Sandbox.boot(port, Builtins::byName, image, props)</pre>
    ///
    /// A bulk `link(Map)` would be strictly worse: it forces the host to
    /// materialise a map of every builtin whether the program reaches it or not,
    /// when `Builtins.TABLE` is already that map and a lookup is already the
    /// operation. `link(name, fn)` then means exactly one thing -- OVERRIDE a name
    /// on a running sandbox -- and its return count tells the host whether the
    /// program uses it at all.
    ///
    /// A name this cannot answer leaves the slot EMPTY rather than failing the
    /// load, which is `Img.load`'s rule and the right one: an image imports every
    /// builtin its namespaces mention, and a program that never calls the missing
    /// one runs fine. The failure names it if it is reached.
    public interface Natives { Builtins.Fn get(String name); }

    // --- state -------------------------------------------------------------

    /// `loop`'s answers. The numbers are the runtime's own `status`, not a
    /// second enumeration on top of it: 0 is settled, 2 is "the host has to do
    /// something".
    public static final int SETTLED = 0, NEEDS_HOST = 2;

    private final Rt rt;
    private final Map<Integer, Bridge> bridges = new HashMap<>();
    private boolean pending;
    private int declared, resolved;

    private Sandbox(Rt rt) { this.rt = rt; }

    // --- 1. boot -----------------------------------------------------------

    /// Load the artifact's image and give the sandbox its door.
    ///
    /// The bridge becomes the SYSTEM port: the one the sandbox can ask the host
    /// for things through. A sandbox given none runs logic and can ask for
    /// nothing, which is a coherent thing to be -- but there is no `boot()`
    /// overload for it, because a second entry point is a second contract.
    ///
    /// `imageChunks` AND `propsText` ARE PASSED IN, and that is the whole
    /// arrangement: they are constant data on the generated `flint.Artifact`
    /// class, which calls this. Nothing here opens a resource, so the program is
    /// not a file the artifact contains -- it is data the artifact is made of, and
    /// the CLR will carry it identically.
    ///
    /// The heap and gas bounds come out of the artifact's own properties rather
    /// than the caller, for the same reason the image does: a consumer has an
    /// artifact, and whoever built it knew what it needs.
    public static Sandbox boot(Bridge system, Natives natives,
                               String[] imageChunks, String propsText) {
        Map<String, byte[]> props = readProps(propsText);
        Rt rt = new Rt(propLong(props, "nursery", 1024 * 1024),
                       propLong(props, "heap", 64L * 1024 * 1024));
        Sandbox box = new Sandbox(rt);
        byte[] image = decode(imageChunks);
        if (Img.load(rt, image) == null) throw new IllegalStateException(
            "the artifact's image is not a flint image, or is a version this runtime does not"
            + " speak (this runtime reads version " + Img.VERSION + ", got " + image.length
            + " bytes in " + imageChunks.length + " chunk(s))");
        // RE-RESOLVED THROUGH THE HOST'S RESOLVER, over whatever `Img.load` bound.
        // `Img` reaches for `Builtins.byName` itself, which is the right default for
        // a host that has the runtime beside it and the wrong one for a host that
        // means to supply its own -- so the resolver wins, and a null answer clears
        // the slot rather than leaving the default in place. Anything else would
        // make "the host supplies the runtime" true only for names the default
        // happened to lack.
        box.declared = rt.nativeNames == null ? 0 : rt.nativeNames.length;
        for (int i = 0; i < box.declared; i++) {
            Builtins.Fn f = natives == null ? null : natives.get(rt.nativeNames[i]);
            rt.natives[i] = f;
            if (f != null) box.resolved++;
        }
        long gas = propLong(props, "gas", 0);
        if (gas > 0) rt.setGasLimit(gas);
        box.wire(system);
        Conc.installSystemPort(rt, system.id, Str.of(rt, system.label));
        return box;
    }

    /// How many builtins this program's image DECLARES, and how many the host's
    /// resolver answered for.
    ///
    /// A diagnostic, not one of the four. It exists because the interesting failure
    /// on this target is silent: a resolver that answers for 60 of 88 produces a
    /// sandbox that works until the program reaches one of the other 28, and the
    /// host has no other way to find out early.
    public int declared() { return declared; }
    public int resolved() { return resolved; }

    /// The image, out of the string constants that carry it.
    ///
    /// ONE CHARACTER PER BYTE, so this is a cast and a copy. The class file stores
    /// each character in modified UTF-8 -- one byte for `0x01`..`0x7F`, two for
    /// `0x00` and for `0x80`..`0xFF` -- and `charAt` gives the character back, so
    /// nothing here decodes anything. The chunking exists because a
    /// `CONSTANT_Utf8_info` length is a `u2`; concatenation is the only thing it
    /// costs.
    private static byte[] decode(String[] chunks) {
        int n = 0;
        for (String s : chunks) n += s.length();
        byte[] out = new byte[n];
        int at = 0;
        for (String s : chunks) {
            for (int i = 0; i < s.length(); i++) out[at++] = (byte) s.charAt(i);
        }
        return out;
    }

    private void wire(Bridge b) {
        if (b.box != null && b.box != this) throw new IllegalStateException(
            "bridge " + b.id + " is already wired to another sandbox");
        b.box = this;
        bridges.put(b.id, b);
    }

    // --- 2. loop -----------------------------------------------------------

    /// Pump until there is no more work, dispatching everything the guest said
    /// on the way.
    ///
    /// Returns `SETTLED` when nothing will ever run again, or `NEEDS_HOST` when
    /// some green thread is parked on a bridge and the host has not answered
    /// yet. NEEDS_HOST is not a failure and not a suspension: the interpreter
    /// simply has nothing runnable, so the host writes to a bridge and calls
    /// this again.
    ///
    /// IT DOES NOT RETURN THE ANSWER. A program's answer comes back as a
    /// message on a bridge, because a bridge is the only door
    /// (`DECISIONS.md#bridges-are-the-only-door`) -- and a `loop` that also
    /// answered would be two contracts, one of which only works for programs
    /// with a distinguished entry point. Those do not exist: a caller names the
    /// function it wants.
    ///
    /// PROGRESS, NOT A SPIN GUARD. `sdks/esm/src/guest.js` loops while the code
    /// is 2 and counts to a million before giving up; here the condition is
    /// whether this turn dispatched anything or whether a `Bridge.send`
    /// enqueued anything, which is the actual question and needs no ceiling.
    public int loop() {
        for (;;) {
            pending = false;
            Conc.drive(rt);
            int status = rt.status;
            boolean progress = dispatch();
            if (status != NEEDS_HOST) return status;
            if (!progress && !pending) return NEEDS_HOST;
        }
    }

    /// Everything pending, handed to the bridges that own it. True if anything
    /// was dispatched.
    ///
    /// The five-`u32` records exist here too, because `Conc.drainEvents` is the
    /// port's shared shape -- but they are UNPACKED HERE rather than by the
    /// host. A wasm host has to do this itself over linear memory.
    private boolean dispatch() {
        Conc.Events evs = Conc.drainEvents(rt);
        if (evs.count() == 0) return false;
        byte[] b = evs.bytes();
        for (int i = 0; i < evs.count(); i++) {
            int rec = i * 20;
            int kind = (int) u32(b, rec), a = (int) u32(b, rec + 4), bb = (int) u32(b, rec + 8);
            int off = (int) u32(b, rec + 12), len = (int) u32(b, rec + 16);
            byte[] payload = new byte[len];
            System.arraycopy(b, off, payload, 0, len);
            switch (kind) {
                case Conc.EV_MESSAGE -> { Bridge p = bridges.get(a); if (p != null) p.message(payload); }
                case Conc.EV_CLOSED -> { Bridge p = bridges.get(a); if (p != null) p.closed(); }
                // `bb` is the SYSTEM port the request came out on, not a port
                // made for it: there is no port until the host grants one.
                case Conc.EV_OPEN -> {
                    Bridge on = bridges.get(bb);
                    Bridge granted = on == null ? null : on.open(capabilityName(payload), payload);
                    if (granted == null) { Conc.hostContinue(rt, a, false); break; }
                    wire(granted);
                    if (!Conc.hostGrant(rt, a, granted.id)) Conc.hostContinue(rt, a, false);
                }
                case Conc.EV_REQUEST -> {
                    Bridge on = bridges.get(bb);
                    byte[] answer = on == null ? null : on.request(payload);
                    if (answer == null) Conc.hostContinue(rt, a, false);
                    else if (!Conc.hostAnswer(rt, a, answer)) Conc.hostContinue(rt, a, false);
                }
                // EV_RETAIN / EV_RELEASE are the host's reference count on its
                // own ports. Nothing here keeps one: this sandbox holds every
                // bridge it was handed for its whole life, so a count of
                // holders has nothing to say to it. A host that shares a port
                // between sandboxes needs them; that host is not this class.
                default -> { }
            }
        }
        return true;
    }

    // --- 3. link -----------------------------------------------------------

    /// Wire a runtime hook in BY NAME.
    ///
    /// Returns how many of this image's native slots took the hook -- 0 means
    /// the program never imports that name, which is worth knowing and is not
    /// an error. A hook for something the program does not call cannot be
    /// reached whatever this did.
    ///
    /// IT WORKS AFTER `boot`, which wasm's cannot. `Img.load` resolves natives
    /// through `Builtins.byName` at load time, so on a pointer ABI a hook has to
    /// be an import supplied at instantiation and the ordering is forced. Here
    /// the slots are an array on a live sandbox, so this re-resolves them and
    /// the order stops mattering. It is also PER-SANDBOX for the same reason:
    /// `Builtins.TABLE` is static and one sandbox's hook must not be another's,
    /// so nothing is written to it.
    ///
    /// AOT-compiled code honours this too: compiled code reaches a native
    /// through `rt.natives[idx]` at call time (`Aot.aotNative`), never a cached
    /// reference.
    ///
    /// The signature is the one blemish and it is real: `Builtins.Fn` reads its
    /// arguments straight off a NaN-boxed value stack, which is not something a
    /// consumer of an artifact can write. See the report.
    public int link(String name, Builtins.Fn fn) {
        if (rt.nativeNames == null) return 0;
        int n = 0;
        for (int i = 0; i < rt.nativeNames.length; i++) {
            if (name.equals(rt.nativeNames[i])) { rt.natives[i] = fn; n++; }
        }
        return n;
    }

    // --- 4. prop -----------------------------------------------------------

    /// Read a metadata property of the artifact -- `prop("version", buf)`.
    ///
    /// Returns how many bytes the value NEEDS, and writes as many of them as
    /// fit; -1 if there is no such property. So a caller with a short buffer
    /// learns the length and can ask again, which is what a pointer ABI has to
    /// do and what this keeps doing on purpose.
    ///
    /// The values are FLAT TEXT, flattened at build time. `src/flint/modmeta.cljc`
    /// builds a nested EDN map, and a runtime that had to read one would need an
    /// EDN reader to answer `prop("version")` -- so `src/flint/jvm.cljc`, which
    /// has a printer, flattens it to one `key\tvalue` line each and keeps the
    /// whole map under `meta` for a caller that wants it. Nothing here parses EDN.
    ///
    /// STATIC, and `propsText` is passed in for the same reason `boot`'s image is:
    /// it is constant data on the generated class. A runner decides whether to
    /// load an artifact at all from what it says about itself, so this answers
    /// with no sandbox alive. `flint inspect` reads a wasm module's custom section
    /// without instantiating it, and this is the same question.
    public static int prop(String name, byte[] buf, String propsText) {
        byte[] v = readProps(propsText).get(name);
        if (v == null) return -1;
        if (buf != null) System.arraycopy(v, 0, buf, 0, Math.min(v.length, buf.length));
        return v.length;
    }

    /// Every property name the artifact has. Not one of the four -- a
    /// diagnostic, so `prop` is discoverable without a list kept somewhere else.
    public static List<String> propNames(String propsText) {
        return new ArrayList<>(readProps(propsText).keySet());
    }

    // --- the property text -------------------------------------------------

    private static Map<String, byte[]> readProps(String raw) {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) out.put(line.substring(0, t), line.substring(t + 1).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static long propLong(Map<String, byte[]> props, String name, long dflt) {
        byte[] v = props.get(name);
        if (v == null) return dflt;
        try { return Long.parseLong(new String(v, StandardCharsets.UTF_8).trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    /// The capability an `open` asked for, out of the encoded argument vector.
    ///
    /// NOT A DECODER. The payload is `[name & args]` as one encoded value, and
    /// reading the name properly means the wire codec -- which belongs in an SDK
    /// beside `sdks/esm/src/codec.js`, not in the runtime. This reads the first
    /// string it can see so a host's `open` handler has something to switch on,
    /// and the whole payload goes with it so a host with a real decoder can
    /// ignore this.
    private static String capabilityName(byte[] payload) {
        for (int i = 0; i + 5 <= payload.length; i++) {
            if ((payload[i] & 0xff) != 5) continue;          // Codec.K_STRING
            long n = u32(payload, i + 1);
            if (n <= 0 || i + 5 + n > payload.length) continue;
            return new String(payload, i + 5, (int) n, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8)
             | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
    }
}
