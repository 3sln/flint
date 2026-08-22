//! Floating-point functions that live in `std` on a host and in `libm` under
//! `no_std`. `libm` is the one dependency flint takes, and this is why: without
//! it there is no `sqrt` in a bare wasm build, and `clojure.math` would be an
//! empty namespace.

macro_rules! f1 {
    ($($name:ident => $host:ident),* $(,)?) => {
        $(
            #[inline]
            pub fn $name(x: f64) -> f64 {
                #[cfg(target_arch = "wasm32")] { libm::$name(x) }
                #[cfg(not(target_arch = "wasm32"))] { f64::$host(x) }
            }
        )*
    };
}

macro_rules! f2 {
    ($($name:ident => $host:ident),* $(,)?) => {
        $(
            #[inline]
            pub fn $name(x: f64, y: f64) -> f64 {
                #[cfg(target_arch = "wasm32")] { libm::$name(x, y) }
                #[cfg(not(target_arch = "wasm32"))] { f64::$host(x, y) }
            }
        )*
    };
}

f1!(
    trunc => trunc, floor => floor, ceil => ceil, round => round, sqrt => sqrt, cbrt => cbrt,
    exp => exp, exp2 => exp2, expm1 => exp_m1, log => ln, log2 => log2, log10 => log10,
    log1p => ln_1p, sin => sin, cos => cos, tan => tan, asin => asin, acos => acos, atan => atan,
    sinh => sinh, cosh => cosh, tanh => tanh, asinh => asinh, acosh => acosh, atanh => atanh
);

f2!(pow => powf, atan2 => atan2, hypot => hypot);

#[inline]
pub fn rint(x: f64) -> f64 {
    // Round half to even, as java.lang.Math.rint does.
    #[cfg(target_arch = "wasm32")]
    {
        libm::rint(x)
    }
    #[cfg(not(target_arch = "wasm32"))]
    {
        let r = x.round();
        if (x - x.trunc()).abs() == 0.5 && r % 2.0 != 0.0 {
            r - x.signum()
        } else {
            r
        }
    }
}

#[inline]
pub fn abs(x: f64) -> f64 {
    f64::from_bits(x.to_bits() & !(1u64 << 63))
}

#[inline]
pub fn signum(x: f64) -> f64 {
    if x.is_nan() {
        f64::NAN
    } else if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        x
    }
}

pub const PI: f64 = core::f64::consts::PI;
pub const E: f64 = core::f64::consts::E;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_the_obvious_values() {
        assert_eq!(sqrt(4.0), 2.0);
        assert_eq!(pow(2.0, 10.0), 1024.0);
        assert_eq!(floor(-1.5), -2.0);
        assert_eq!(ceil(-1.5), -1.0);
        assert_eq!(trunc(-1.5), -1.0);
        assert_eq!(abs(-0.0).to_bits(), 0.0f64.to_bits());
        assert_eq!(signum(-3.0), -1.0);
        assert!((log(E) - 1.0).abs() < 1e-15);
        assert!((atan2(1.0, 1.0) - PI / 4.0).abs() < 1e-15);
        // rint rounds half to even
        assert_eq!(rint(2.5), 2.0);
        assert_eq!(rint(3.5), 4.0);
        assert_eq!(rint(-2.5), -2.0);
    }
}
