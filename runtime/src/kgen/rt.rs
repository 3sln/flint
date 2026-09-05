//! `flint.rt.*` -- one module per kin source. See `../kgen.rs`.
//!
//! One line per source, and `bin/check-kin` fails when a source has no line
//! here, because a missing `pub mod` in Rust is not a compile error -- it is
//! a module that quietly never gets built.
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
pub mod ropeflat;
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
