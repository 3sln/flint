package com.flint;

/// A thrown error: kind, message, data.
///
/// A DISTINCT type, not a map, matching `runtime/src/err.rs` where an exception
/// is its own object with `EX_KIND`, `EX_MSG` and `EX_DATA` slots.
///
/// The difference is not cosmetic, and it cost a debugging session. Builtins
/// here used to throw a bare string, so `ex-message` on one answered nil -- and
/// the flint compiler, which catches an error and re-throws it with the form it
/// happened in, produced `"\n  in flint.main/-main"`: a location with no
/// message in front of it. The failure was real and the report said nothing,
/// which is the worst combination.
public final class Ex {
    public final String kind;
    public final Object message;
    public final Object data;

    public Ex(String kind, Object message, Object data) {
        this.kind = kind;
        this.message = message;
        this.data = data;
    }

    @Override public String toString() {
        return kind + ": " + Builtins.str(message);
    }
}
