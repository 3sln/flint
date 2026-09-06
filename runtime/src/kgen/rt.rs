//! `flint.rt.*` -- one module per kin source. See `../kgen.rs`.
//!
//! One line per source, and `bin/check-kin` fails when a source has no line
//! here, because a missing `pub mod` in Rust is not a compile error -- it is
//! a module that quietly never gets built.
//!
//! `unused_comparisons` IS AN ERROR, and since `schema_id` it is denied for
//! the WHOLE CRATE at `lib.rs` rather than only here. `I32` is `u32` in
//! Rust and `int` in the ports, so `(if (< x 0) ...)` after a subtraction
//! reads as a clamp in two targets and is dead code in the third -- where the
//! subtraction has already wrapped to a huge number instead of going
//! negative. That shape has shipped twice now: once in `b-at`, once in
//! `s-append-range`, both caught downstream by a probe rather than here.
//!
//! rustc already sees it -- "comparison is useless due to type limits" -- but
//! as one warning among forty. Denying it turns the tell into a build failure
//! at the file that has the bug.
//!
//! It catches the TELL, not the underflow. A source that subtracts into the
//! negative and never guards it stays silent. The guarded form -- subtract
//! only when the result is known non-negative -- is what the sources use, and
//! this makes the unguarded one impossible to leave in by accident.
pub mod assoc;
pub mod byteconcat;
pub mod byteat;
pub mod byteslice;
pub mod bytecore;
pub mod byteeq;
pub mod byteflat;
pub mod bytehash;
pub mod bytefold;
pub mod bytetwrite;
pub mod bytenode;
pub mod bytetrans;
pub mod champ;
pub mod collnode;
pub mod copies;
pub mod nouns;
pub mod dissoc;
pub mod eq;
pub mod eqalloc;
pub mod find;
pub mod hash;
pub mod interns;
pub mod mapcore;
pub mod mapread;
pub mod mapwrite;
pub mod merge;
pub mod nodeclass;
pub mod pike;
pub mod ropecat;
pub mod ropecp;
pub mod ropeeq;
pub mod ropeflat;
pub mod ropemeas;
pub mod tablebuild;
pub mod tablecell;
pub mod tablefill;
pub mod tablekind;
pub mod tablemake;
pub mod tablemeta;
pub mod tablemigrate;
pub mod tablesay;
pub mod tabletrans;
pub mod tableref;
pub mod ropenode;
pub mod ropeslice;
pub mod seqcore;
pub mod seqs;
pub mod seqwalk;
pub mod vecnode;
pub mod vecread;
pub mod vecassoc;
pub mod vecwrite;
pub mod vectwrite;
pub mod vectrans;
