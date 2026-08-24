// A document store capability, and the fetch planner that lives with it.
//
// `doc/decisions/0008` puts planning on the HOST on purpose: only the host knows
// the storage's latency and bandwidth and the memory budget, so only the host
// can decide how many requests to make and how wide each one should be. The
// script asks for pieces; this decides how to get them.

/// Merge a sorted list of intervals when the gap between them is cheaper to
/// fetch than a second request would be.
///
///     gap_bytes / bandwidth  <  request_latency
///
/// Put real numbers in and the answer is startling. At 20 ms per request and
/// 100 MB/s a round trip is worth about **2 MB of bandwidth**, so the
/// break-even gap is measured in megabytes and the right policy is far more
/// aggressive coalescing than instinct suggests: fetching `a` through `c` and
/// throwing `b` away is correct for a very large `b`. The two constants are
/// measured, not assumed — `bench/docbench.mjs` measures them for whatever
/// store it is pointed at.
export function coalesce(intervals, gapThreshold) {
  const sorted = [...intervals].sort((x, y) => x[0] - y[0]);
  const out = [];
  for (const [s, e] of sorted) {
    const last = out[out.length - 1];
    if (last && s - last[1] <= gapThreshold) {
      last[1] = Math.max(last[1], e);
    } else {
      out.push([s, e]);
    }
  }
  return out;
}

/// The gap at which one more request costs the same as fetching the gap.
export function breakEvenGap({ latencyMs, bytesPerMs }) {
  return Math.round(latencyMs * bytesPerMs);
}

/// A store over an in-memory content buffer. `latencyMs` and `bytesPerMs`
/// describe the storage it is standing in for; `budgetBytes` is how much may be
/// resident in one wave.
export class DocStore {
  constructor(structure, content, opts = {}) {
    this.structure = structure;             // {root, nodes:[{id,...,off,len}]}
    this.content = content;                 // Uint8Array
    this.latencyMs = opts.latencyMs ?? 20;
    this.bytesPerMs = opts.bytesPerMs ?? 100_000; // 100 MB/s
    this.budgetBytes = opts.budgetBytes ?? 64 * 1024;
    this.gapThreshold = opts.gapThreshold ?? breakEvenGap(this);
    this.byId = new Map(structure.nodes.map((n) => [n.id, n]));
    // What the benchmarks want to know.
    this.stats = { requests: 0, bytesFetched: 0, bytesDelivered: 0, waves: 0 };
  }

  /// One storage request. Counted and measured; the delay is simulated so the
  /// planner can be exercised against different storage profiles.
  fetchRange(start, end) {
    this.stats.requests += 1;
    this.stats.bytesFetched += end - start;
    return this.content.subarray(start, end);
  }

  /// Plan and run the fetch for a set of node ids, calling `emit(waveObject)`
  /// once per wave.
  ///
  /// Two things the naive version misses, both from 0008:
  ///
  /// * **Discarded bytes never enter the guest heap.** When `a`–`c` is fetched
  ///   to get `a` and `c`, the `b` in the middle is dropped *here*, at the
  ///   boundary. Otherwise over-fetching would cost memory as well as
  ///   bandwidth and the whole coalescing policy would invert.
  /// * **An ask larger than the budget is answered in waves**, because refusing
  ///   it would only push the chunking into every caller, and they will each get
  ///   it wrong differently.
  /// Plan the fetch and return an iterator of waves. **Lazy on purpose**: a
  /// server that produced every wave at once would hold the whole answer, which
  /// is the memory it was asked not to hold. The caller pulls the next wave when
  /// the guest has taken the last.
  contentPlan(ids) {
    const store = this;
    const wanted = ids.map((id) => this.byId.get(id)).filter((n) => n && n.len > 0);
    const plan = coalesce(wanted.map((n) => [n.off, n.off + n.len]), this.gapThreshold);
    const dec = new TextDecoder();
    let queue = [];            // resolved [id text] awaiting a wave
    let i = 0;                 // position in `wanted`
    let done = false;
    const fetched = new Set(); // plan entries already pulled from storage

    // Which planned range covers this node. Every wanted interval is contained
    // in exactly ONE coalesced entry by construction, since coalesce only ever
    // merges or extends -- so -1 means the planner is wrong, not that the node
    // is unwanted.
    //
    // This used to be a cursor that advanced through the plan as it scanned. A
    // cursor is only correct if the caller asks in ascending offset order, and
    // nothing makes them: `wanted` is in the order ASKED, `plan` is sorted by
    // offset. Once the cursor passed an entry, every node still needing it
    // became unreachable.
    const entryFor = (n) => {
      let lo = 0;
      let hi = plan.length - 1;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        const [s, e] = plan[mid];
        if (n.off < s) hi = mid - 1;
        else if (n.off + n.len > e) lo = mid + 1;
        else return mid;
      }
      return -1;
    };

    const resolve = (n) => {
      const k = entryFor(n);
      if (k < 0) {
        // A node the planner did not fully cover. Raising is the whole point:
        // the alternative is to skip it, which delivers a short answer and
        // reports success -- a wrong answer instead of an error.
        throw new Error(
          `docstore: node ${n.id} spanning ${n.off}..${n.off + n.len} is not covered ` +
          `by any planned range (${JSON.stringify(plan)})`);
      }
      if (fetched.has(k)) return;
      fetched.add(k);
      const [s, e] = plan[k];
      const buf = store.fetchRange(s, e);
      for (const w of wanted) {
        if (w.off >= s && w.off + w.len <= e) {
          // Only the wanted sub-range is kept. The gap between is dropped
          // HERE, at the boundary -- otherwise over-fetching would cost
          // memory as well as bandwidth and the whole policy would invert.
          queue.push([w.id, dec.decode(buf.subarray(w.off - s, w.off + w.len - s))]);
        }
      }
    };

    return {
      next() {
        if (done) return null;
        const wave = [];
        let bytes = 0;
        while (i < wanted.length) {
          const n = wanted[i];
          if (!queue.some(([id]) => id === n.id)) resolve(n);
          const at = queue.findIndex(([id]) => id === n.id);
          if (at < 0) {
            // Unreachable unless resolve() is wrong. It must never become
            // `i += 1; continue`, which would advance as though the node had
            // been delivered and lose a wave of content in silence.
            throw new Error(`docstore: node ${n.id} was planned but not resolved`);
          }
          if (bytes > 0 && bytes + n.len > store.budgetBytes) break;
          const [, text] = queue[at];
          queue.splice(at, 1);
          wave.push([n.id, text]);
          bytes += n.len;
          store.stats.bytesDelivered += n.len;
          i += 1;
        }
        const final = i >= wanted.length;
        if (final) done = true;
        store.stats.waves += 1;
        return { wave, final };
      },
    };
  }

  contentFor(ids, emit) {
    const wanted = ids
      .map((id) => this.byId.get(id))
      .filter((n) => n && n.len > 0);

    // Intervals, coalesced by the cost model.
    const plan = coalesce(wanted.map((n) => [n.off, n.off + n.len]), this.gapThreshold);

    // Delivery order is defined: the order the caller ASKED, not the order the
    // planner happened to choose. A script whose behaviour depends on how the
    // planner coalesced is not deterministic.
    const dec = new TextDecoder();
    const resolved = new Map();
    for (const [s, e] of plan) {
      const buf = this.fetchRange(s, e);
      for (const n of wanted) {
        if (n.off >= s && n.off + n.len <= e) {
          // Only the wanted sub-range is kept; the gap between is dropped here.
          resolved.set(n.id, dec.decode(buf.subarray(n.off - s, n.off + n.len - s)));
        }
      }
    }

    // A wave is a vector of [id text] pairs rather than a map: it keeps the
    // asked-for order on the wire, and it sidesteps the question of what a
    // numeric map key is in whatever format the port is using.
    let wave = [];
    let waveBytes = 0;
    let sent = 0;
    const flush = (final) => {
      this.stats.waves += 1;
      emit(wave, final);
      wave = [];
      waveBytes = 0;
    };
    for (const n of wanted) {
      const text = resolved.get(n.id);
      if (text === undefined) {
        throw new Error(`docstore: node ${n.id} was planned but not resolved`);
      }
      if (waveBytes > 0 && waveBytes + n.len > this.budgetBytes) flush(false);
      wave.push([n.id, text]);
      waveBytes += n.len;
      this.stats.bytesDelivered += n.len;
      sent += 1;
    }
    flush(true);
    return sent;
  }
}

/// The capability handler `host/flint.mjs` expects. Requests and replies are
/// EDN-ish maps carried by whatever codec the script opened the port with.
export function documentCapability(store, codec) {
  // One in-flight plan per port, pulled from as the guest makes room.
  const inflight = new Map();
  const offer = (port, api) => {
    const job = inflight.get(port);
    if (!job) return;
    const next = job.plan.next();
    if (!next) { inflight.delete(port); return; }
    api.deliver(port, codec.encode({ id: job.id, body: next.wave, final: next.final }));
    if (next.final) inflight.delete(port);
  };
  return {
    /// Called once per pump: offer the next wave, now that the last has been
    /// taken. This is where "the caller processes and releases, and the next
    /// wave arrives" actually happens.
    poll(port, api) { offer(port, api); },
    message(port, data, api) {
      const req = codec.decode(data);
      // A keyword stays a keyword across an EDN port -- that is the point of
      // using EDN rather than JSON -- so unwrap it rather than comparing a
      // Keyword to a string and silently never matching.
      const op = req.op && req.op.name !== undefined ? req.op.name : req.op;
      const reply = (body, final, id) => api.deliver(port, codec.encode({ id, body, final }));
      if (op === 'structure') {
        // Structure carries no text: that is the whole point of the split.
        reply(
          {
            root: store.structure.root,
            nodes: store.structure.nodes.map(({ id, type, page, box, parent, children, len }) => ({
              id, type, page, box, parent, children, len,
            })),
          },
          true,
          req.id,
        );
      } else if (op === 'content') {
        inflight.set(port, { id: req.id, plan: store.contentPlan(req.nodes) });
        offer(port, api);
      } else if (op === 'cancel') {
        inflight.delete(port);
      } else {
        api.deliver(port, codec.encode({ id: req.id, error: `no such op: ${op}`, final: true }));
      }
    },
  };
}
