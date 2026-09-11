package com.flint.rt;

import java.util.ArrayList;

/// What every executor in one sandbox SHARES (`DECISIONS.md#drivers`).
///
/// The split is the whole of what makes several host threads on one heap
/// possible. A value stack and a shadow stack are one PER THREAD -- two
/// threads interpreting at once each have their own -- while var slots,
/// constants, singletons and the intern tables are one per SANDBOX, because
/// they are the program rather than the execution.
///
/// Getting this the other way round is not a performance question. A shared
/// value stack would be two threads pushing to one array; a per-thread intern
/// table would be two copies of a string that must be `=`, and `=` on two
/// interned strings is a pointer compare.
public final class Shared {
    /// EVERY executor in this sandbox, registered once when it is created.
    ///
    /// Registered rather than published at each park because an executor's
    /// roots do not move, and a list built once cannot race with a list being
    /// built.
    public final ArrayList<Roots> all = new ArrayList<>();

    /// Every OTHER executor, filled in only BETWEEN `stageStop` and
    /// `releaseStop` and empty outside it.
    ///
    /// Transient on purpose: a list that outlived the stop would be roots
    /// belonging to threads that have started running again, and the collector
    /// would walk them while they were being mutated.
    public final ArrayList<Roots> others = new ArrayList<>();

    /// Var slots, read by VAR and written by SET_VAR.
    ///
    /// Several executors read these while one may be writing. On the Rust side
    /// they are atomic; here a `long[]` is enough, because the JVM guarantees
    /// that a `long` read sees a whole value rather than half of one when it is
    /// written through a volatile-free path only on 64-bit -- and flint is
    /// 64-bit everywhere. A var slot carries no happens-before for anything
    /// else: the heap object it names is published by the allocation lock and
    /// the safepoint.
    public long[] globals = new long[0];
    public long[] consts = new long[0];
    public long[] singletons = newSingletons();

    /// The intern tables. WEAK, and rewritten by the collector at a safepoint.
    public final Interns[] interns = Interns.tables();

    /// The safepoint protocol. One per sandbox, because that is the unit it
    /// stops.
    public final Parallel par = new Parallel();

    static long[] newSingletons() {
        long[] s = new long[Rt.SING_COUNT];
        // FILLED WITH NIL, not zero: 0 is the bit pattern of `+0.0`, so an
        // unset slot read back as the double zero and `dyn-bindings` answered
        // a number where a map was expected.
        java.util.Arrays.fill(s, Val.NIL);
        return s;
    }
}
