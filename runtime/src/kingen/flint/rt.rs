//! `flint.rt.*` -- one module per kin source. See `../../kingen.rs`.
//!
//! One line per source, and `bin/check-kin` fails when a source has no line
//! here, because a missing `pub mod` in Rust is not a compile error -- it is
//! a module that quietly never gets built.
pub mod assoc;
pub mod champ;
pub mod collnode;
pub mod copies;
pub mod dissoc;
pub mod eq;
pub mod eqalloc;
pub mod find;
pub mod hash;
pub mod interns;
pub mod merge;
pub mod nodeclass;
pub mod pike;
pub mod seqs;
