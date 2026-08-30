package com.flint.rt;

/// One flint call, ported from `runtime/src/vm.rs`.
///
/// Deliberately does NOT cache the closure. It used to, in the Rust, and that
/// copy was a root the collector could not see: after a collection moved the
/// closure, `UPVAL` and `SELF` read a stale address. `stack[retTo]` IS this
/// frame's closure until it returns, and the return value overwrites it -- so
/// the invariant the whole GC design rests on, that every live reference is in
/// the value stack, holds with no second mechanism.
public final class Frame {
    public int fp;
    public int ip;
    public int end;
    public int retTo;
    public int handlers;

    /// Index into the image's compiled arities, or `Aot.NONE`. Set by `enter`
    /// from the arity it SELECTED, so the whole AOT question is one field on the
    /// frame rather than a lookup keyed on something the frame does not carry.
    public int aotIdx = Aot.NONE;
    /// Re-enter compiled code when `ip` reaches this, at `aotBlock`. Every
    /// re-entry point in the design funnels through this one comparison.
    public int aotIp = Aot.NEVER;
    public int aotBlock;
}
