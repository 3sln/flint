# Benchmarks

## Benchmarks

Apple M1 Pro, Darwin 23.6.0, node v24.6.0. Method: best of 5 runs per
measurement, one node process per program; `cold` is `WebAssembly.compile` plus
instantiate plus the first `main()`, `warm` is a second `main()` on the same
instance. Full output, including the native runs, in
[`doc/benchmarks.txt`](doc/benchmarks.txt); reproduce with `./bin/bench`.

### Module size and cold start

| program | bytes | compile | cold | warm |
|---|---:|---:|---:|---:|
| hello (trivial) | 179 539 | 0.05 ms | 0.11 ms | 0.01 ms |
| tight loop, 10⁶ iterations | 203 861 | 0.11 ms | 169.71 ms | 169.37 ms |
| transient map, 10⁵ inserts | 218 531 | 0.10 ms | 47.33 ms | 46.76 ms |
| word frequency (string split) | 250 689 | 0.10 ms | 63.17 ms | 62.44 ms |
| word frequency (regex split) | 269 013 | 0.08 ms | 113.36 ms | 113.15 ms |
| JSON round trip, 2000 records | 290 398 | 0.10 ms | 104.36 ms | 103.50 ms |

And the cost of the host boundary, from `test/host_abi.mjs` on the same machine:
a message drained one at a time costs **11 833 ns**; a thousand drained in one
call cost **1 483 ns each**. The boundary crossing is nothing; the marshalling
is everything, which is why messages are serialised at send time and the queue
is drained whole.

Cold start is dominated by the work itself: the module's own startup — reserving
the heap, loading the image, running every top-level initialiser — is the 0.10 ms
gap between `compile` and `cold` on the trivial program.

### Against babashka

The fairest available baseline: another non-JIT Clojure, same machine, same
source, same input. It is **not** a claim about JVM Clojure.

| program | flint | babashka | ratio |
|---|---:|---:|---:|
| tight loop, 10⁶ iterations | 169.37 ms | 76.58 ms | 2.21× |
| transient map, 10⁵ inserts | 46.76 ms | 18.32 ms | 2.55× |
| word frequency (regex split) | 113.15 ms | 1.32 ms | **86×** |

Two and a half times slower than babashka on interpreter-bound and
data-structure-bound work is a fair place to be for a self-contained module with
its own collector. The regex number is not; see [Limits](#limits).

### Dispatch, isolated from data-structure cost

| program | instructions | warm | ns / instruction |
|---|---:|---:|---:|
| tight loop, 10⁶ iterations | 27 000 226 | 169.37 ms | 6.3 |
| JSON round trip | 12 330 891 | 103.50 ms | 8.4 |
| transient map, 10⁵ inserts | 3 000 323 | 46.76 ms | 15.6 |
| word frequency (string split) | 3 257 997 | 62.44 ms | 19.2 |

Read down the column: 6.2 ns is what a dispatched instruction costs when it does
almost nothing, and the rising numbers are the same dispatch diluted by real
work. Dispatch is about a third of the time on allocation-light code and much
less on anything that touches the heap.

### Transients versus persistents (native, no interpreter)

| operation | persistent | transient | speedup |
|---|---:|---:|---:|
| vector `conj`, 10⁵ | 44.6 ns/op | 4.4 ns/op | **10.1×** |
| map `assoc`, 10⁵ | 422.5 ns/op | 189.2 ns/op | 2.2× |
| map `assoc`, 10³ | 154.5 ns/op | 81.2 ns/op | 1.9× |
| map `assoc`, 64 | 87.2 ns/op | 63.8 ns/op | 1.4× |
| map `assoc`, 8 | 41.6 ns/op | 57.4 ns/op | 0.7× |

Transients are genuinely fast, which matters because the compiler is written in
cljc and compiles itself — transient performance is on flint's own critical
path, not a nice-to-have. The 8-entry row is the exception and is expected:
`transient` on an array-map promotes it to a CHAMP trie, so for a map that small
the promotion costs more than it saves.

### Collections at several sizes (native)

| operation | 8 | 64 | 10³ | 10⁵ |
|---|---:|---:|---:|---:|
| map `assoc` | 41.6 ns | 87.2 ns | 154.5 ns | 422.5 ns |
| map `get` | 20.8 ns | 12.4 ns | 21.4 ns | 26.8 ns |
| vector `nth` | | | | 1.9 ns |
| vector `conj` | | | | 44.6 ns |
| set `conj` | | | | 437.5 ns |
| keyword intern lookup | | | | 10.2 ns |

`get` staying flat from 64 to 100 000 entries is the trie doing its job.

### Collector

Given above: median minor pause 12.9 µs, median major pause 165 µs, 6.7% of wall
clock on an allocation-heavy workload.
