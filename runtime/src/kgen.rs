//! Generated code, kept in a subtree of its own.
//!
//! `crate::kgen::rt::*`, mirroring `runtime/src/kgen/rt/`. The leading
//! `flint` of each kin namespace is dropped: the crate is already the root,
//! and `crate::kgen::flint::rt` would say it twice. Java and C# get their own
//! conventions -- reverse-DNS there, PascalCase and no reverse-DNS here -- and
//! the rule they share is that the namespace MIRRORS THE DIRECTORY.
//!
//! Everything under here is written by kin from the sources in `kin/*.kin`
//! and must not be edited: `bin/check-kin` re-generates it and compares.
//!
//! THE DECLARATIONS BELOW ARE THE ONE THING kin DOES NOT WRITE. It reports
//! them -- `kin/scripts/destinations` lists every file it produces -- and a
//! person applies them, because a generator that writes into hand-written
//! files is the splice this subtree exists to delete.
//!
//! Rust is the target where forgetting one is SILENT: a module nobody
//! declares is not compiled at all, and the build stays green with the code
//! simply absent. `bin/check-kin` therefore checks the list rather than
//! trusting it.
pub mod rt;
