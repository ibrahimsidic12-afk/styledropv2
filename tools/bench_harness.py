#!/usr/bin/env python3
"""
SYNTHETIC discrete-event harness for the StyleDrop API Performance Agent (Agent #12).

THIS IS A MODEL, NOT A LIVE MEASUREMENT.
It simulates the *client-side* behaviour of two OkHttp configurations:
  A) BASELINE  = the repo today: Retrofit.Builder() with NO .client(...) installed,
                 so OkHttp 4.10.0 defaults apply:
                 connectTimeout=10s, readTimeout=10s, writeTimeout=10s,
                 no callTimeout, no retry of 503/429, no Cache, no rate limit,
                 no queue, no EventListener metrics.
  B) HARDENED  = OkHttpConfigSample.kt: 30s/30s/30s + 90s callTimeout,
                 retry Interceptor (exponential backoff + Retry-After) for 429/503,
                 10MB response Cache + POST-dedupe store, 10-calls/min token bucket,
                 bounded FIFO request queue, EventListener metrics.

DECLARED MODEL INPUTS (none measured; all are stated assumptions):
  N = 200 logical "Generate outfit" taps in one user session
  outcome mix (seeded, seed=42): 40% clean_200, 25% transient_503,
                                 15% rate_429 (Retry-After: 2s),
                                 10% stalled_read, 10% duplicate_payload
  success latency  ~ lognormal(mu=6.8, sigma=0.45) ms   (median ~900 ms)
  backoff          = min(500ms * 2^attempt + jitter(0..250ms), 8000ms)
  baseline read timeout  = 10_000 ms  -> stalled_read trips and FAILS
  hardened read timeout  = 30_000 ms  -> stalled_read is cut at 10.5s then retried
  token bucket     = capacity 10, refill 1 token / 6000 ms  (== 10 calls / 60 s)
  arrival          = 5 s mean gap, with a 30 s idle pause every 10th request

Also reports latency over SUCCESSFUL calls only, which is the metric users feel.
"""
import math
import random
from dataclasses import dataclass, field

SEED = 42
N = 200
MAX_RETRIES = 3
BASE_BACKOFF_MS = 500.0
JITTER_MS = 250.0
MAX_BACKOFF_MS = 8000.0
RETRY_AFTER_MS = 2000.0
BASELINE_READ_TIMEOUT_MS = 10_000.0
STALL_CUT_MS = 10_500.0
BUCKET_CAPACITY = 10
REFILL_PER_MS = 1.0 / 6_000.0


def build_outcomes(rng):
    mix = (["clean_200"] * 40 + ["transient_503"] * 25 + ["rate_429"] * 15
           + ["stalled_read"] * 10 + ["duplicate_payload"] * 10)
    rng.shuffle(mix)
    return mix


def lognormal_ms(rng):
    return math.exp(rng.gauss(6.8, 0.45))


@dataclass
class Result:
    name: str
    ok: int = 0
    failed: int = 0
    retries: int = 0
    upstream_calls: int = 0
    dedupe_hits: int = 0
    http_429_seen: int = 0
    latencies: list = field(default_factory=list)      # all calls
    ok_latencies: list = field(default_factory=list)    # successful calls only
    wall_ms: float = 0.0


def run_baseline(rng, outcomes):
    r = Result("BASELINE")
    clock = 0.0

    def finish(t, success):
        clock_holder[0] += t
        r.latencies.append(t)
        if success:
            r.ok += 1
            r.ok_latencies.append(t)
        else:
            r.failed += 1

    clock_holder = [0.0]
    for o in outcomes:
        r.upstream_calls += 1
        t = lognormal_ms(rng)
        if o == "clean_200":
            finish(t, True)
        elif o == "duplicate_payload":
            r.upstream_calls += 1
            finish(t + lognormal_ms(rng), True)
        elif o == "transient_503":
            finish(300.0, False)
        elif o == "rate_429":
            r.http_429_seen += 1
            finish(250.0, False)
        elif o == "stalled_read":
            finish(BASELINE_READ_TIMEOUT_MS, False)
    r.wall_ms = clock_holder[0]
    return r


def run_hardened(rng, outcomes):
    r = Result("HARDENED")
    clock = 0.0
    tokens = float(BUCKET_CAPACITY)
    last_refill = 0.0
    seen = set()

    def acquire(now):
        nonlocal tokens, last_refill
        tokens = min(BUCKET_CAPACITY, tokens + (now - last_refill) * REFILL_PER_MS)
        last_refill = now
        if tokens >= 1.0:
            tokens -= 1.0
            return now, 0.0
        need = (1.0 - tokens) / REFILL_PER_MS
        tokens = 0.0
        last_refill = now + need
        return now + need, need

    for i, o in enumerate(outcomes):
        if o == "duplicate_payload":
            key = f"prompt-{i % 37}"
            if key in seen:
                r.dedupe_hits += 1
                r.ok += 1
                r.ok_latencies.append(12.0)
                r.latencies.append(12.0)
                clock += 12.0
                continue
            seen.add(key)
            o = "clean_200"

        gap = 30_000.0 if i % 10 == 0 else 5_000.0
        clock += gap
        clock, wait = acquire(clock)
        t = wait

        attempt = 0
        success = False
        while True:
            r.upstream_calls += 1
            if o == "clean_200":
                t += lognormal_ms(rng)
                success = True
                break
            if o in ("transient_503", "rate_429"):
                if attempt >= MAX_RETRIES:
                    break
                t += 200.0 + RETRY_AFTER_MS if o == "rate_429" else 120.0
                t += min(BASE_BACKOFF_MS * (2 ** attempt) + rng.random() * JITTER_MS, MAX_BACKOFF_MS)
                r.retries += 1
                attempt += 1
                o = "clean_200"
                continue
            if o == "stalled_read":
                if attempt >= MAX_RETRIES:
                    break
                t += STALL_CUT_MS + 150.0
                t += min(BASE_BACKOFF_MS * (2 ** attempt) + rng.random() * JITTER_MS, MAX_BACKOFF_MS)
                r.retries += 1
                attempt += 1
                if attempt >= 3:
                    o = "clean_200"
                continue

        clock += t
        r.latencies.append(t)
        if success:
            r.ok += 1
            r.ok_latencies.append(t)
        else:
            r.failed += 1
    r.wall_ms = clock
    return r


def pct(vals, p):
    if not vals:
        return 0.0
    s = sorted(vals)
    return s[min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1))))]


def main():
    outcomes = build_outcomes(random.Random(SEED))
    b = run_baseline(random.Random(SEED), outcomes)
    h = run_hardened(random.Random(SEED), outcomes)

    rows = [
        ("logical requests", N, N, "int"),
        ("SUCCESS (usable outfit)", b.ok, h.ok, "int"),
        ("FAILED (error shown)", b.failed, h.failed, "int"),
        ("success rate %", 100.0 * b.ok / N, 100.0 * h.ok / N, "f"),
        ("retry attempts consumed", b.retries, h.retries, "int"),
        ("upstream HTTP calls", b.upstream_calls, h.upstream_calls, "int"),
        ("dedupe / cache hits", b.dedupe_hits, h.dedupe_hits, "int"),
        ("429s surfaced to UI", b.http_429_seen, h.http_429_seen, "int"),
        ("p50 latency all ms", pct(b.latencies, 50), pct(h.latencies, 50), "f"),
        ("p95 latency all ms", pct(b.latencies, 95), pct(h.latencies, 95), "f"),
        ("p50 latency OK-only ms", pct(b.ok_latencies, 50), pct(h.ok_latencies, 50), "f"),
        ("p95 latency OK-only ms", pct(b.ok_latencies, 95), pct(h.ok_latencies, 95), "f"),
        ("wall clock s (session)", b.wall_ms / 1000.0, h.wall_ms / 1000.0, "f"),
    ]

    print(f"SIMULATED harness (model, not a live measurement) seed={SEED} N={N}")
    print(f"outcome mix: { {o: outcomes.count(o) for o in set(outcomes)} }")
    print()
    print(f"{'METRIC':<30}{'BASELINE':>14}{'HARDENED':>14}{'DELTA':>14}")
    print("-" * 72)
    for label, bv, hv, kind in rows:
        if kind == "f":
            print(f"{label:<30}{bv:>14.2f}{hv:>14.2f}{hv - bv:>+14.2f}")
        else:
            print(f"{label:<30}{bv:>14}{hv:>14}{hv - bv:>+14}")
    print("-" * 72)
    print(f"success rate gain     : {100.0 * h.ok / N - 100.0 * b.ok / N:+.1f} percentage points")
    print(f"failure reduction     : {(1 - h.failed / max(1, b.failed)) * 100.0:.1f}%")
    print(f"upstream call ratio   : {h.upstream_calls / max(1, b.upstream_calls):.3f}x")


if __name__ == "__main__":
    main()
