import { assert, assertEquals } from "@std/assert";
import type { CacheRow } from "./eval-chain.ts";
import { evaluate } from "./eval-chain.ts";
import {
  CHESS_API_HIT,
  FakeCache,
  isoHoursAgo,
  jsonResponse,
  LICHESS_HIT,
  LICHESS_MISS,
  makeDeps,
  MIDGAME_FEN,
  MIDGAME_FEN_NORM,
  NOW,
  START_FEN,
} from "./test-helpers.ts";

function positiveRow(fen: string, hoursOld: number): CacheRow {
  return {
    fen,
    eval_cp: 22,
    mate: null,
    best_move: "e2e4",
    depth: 36,
    source: "lichess",
    fetched_at: isoHoursAgo(hoursOld),
  };
}

function unknownRow(fen: string, hoursOld: number): CacheRow {
  return {
    fen,
    eval_cp: null,
    mate: null,
    best_move: null,
    depth: null,
    source: "unknown",
    fetched_at: isoHoursAgo(hoursOld),
  };
}

Deno.test("fresh cache hit: provider source preserved + cached flag, no provider call", async () => {
  const cache = new FakeCache();
  cache.rows.set(START_FEN, positiveRow(START_FEN, 1));
  const { deps, calls } = makeDeps({}, cache);

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 200);
  assertEquals(r.body.source, "lichess");
  assertEquals(r.body.cached, true);
  assertEquals(r.body.eval_cp, 22);
  assertEquals(r.body.best_move, "e2e4");
  assertEquals(calls.length, 0);
});

Deno.test("stale positive row (>30 days) is refetched", async () => {
  const cache = new FakeCache();
  cache.rows.set(START_FEN, positiveRow(START_FEN, 31 * 24));
  const { deps, calls } = makeDeps({ "lichess.org": LICHESS_HIT }, cache);

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.body.source, "lichess");
  assertEquals(r.body.cached, false);
  assertEquals(calls.length, 1);
  assertEquals(cache.upserts.length, 1);
  assertEquals(cache.upserts[0].fetched_at, NOW.toISOString());
});

Deno.test("unknown row within 24h is served without provider calls", async () => {
  const cache = new FakeCache();
  cache.rows.set(START_FEN, unknownRow(START_FEN, 23));
  const { deps, calls } = makeDeps({}, cache);

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 200);
  assertEquals(r.body, { fen: START_FEN, source: "unknown" });
  assertEquals(calls.length, 0);
});

Deno.test("unknown row older than 24h is retried", async () => {
  const cache = new FakeCache();
  cache.rows.set(START_FEN, unknownRow(START_FEN, 25));
  const { deps, calls } = makeDeps({ "lichess.org": LICHESS_HIT }, cache);

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.body.source, "lichess");
  assertEquals(calls.length, 1);
});

Deno.test("lichess hit: upserted as source=lichess and returned", async () => {
  const { deps, cache, calls } = makeDeps({ "lichess.org": LICHESS_HIT });

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 200);
  assertEquals(r.body.source, "lichess");
  assertEquals(r.body.eval_cp, 22);
  assertEquals(r.body.mate, null);
  assertEquals(r.body.best_move, "e2e4");
  assertEquals(r.body.depth, 36);
  assertEquals(r.body.fetched_at, NOW.toISOString());
  assertEquals(cache.upserts.length, 1);
  assertEquals(cache.upserts[0].source, "lichess");
  assertEquals(calls.length, 1);
});

Deno.test("lichess 404 → chess-api hit: upserted as source=chess-api", async () => {
  const { deps, cache, calls } = makeDeps({
    "lichess.org": LICHESS_MISS,
    "chess-api.com": CHESS_API_HIT,
  });

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 200);
  assertEquals(r.body.source, "chess-api");
  assertEquals(r.body.eval_cp, 31);
  assertEquals(r.body.best_move, "d2d4");
  assertEquals(cache.upserts.length, 1);
  assertEquals(cache.upserts[0].source, "chess-api");
  assertEquals(calls.length, 2);
});

Deno.test("lichess 404 + chess-api failure: negative-cached as unknown", async () => {
  const { deps, cache } = makeDeps({
    "lichess.org": LICHESS_MISS,
    "chess-api.com": () => jsonResponse(500, {}),
  });

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 200);
  assertEquals(r.body, { fen: START_FEN, source: "unknown" });
  assertEquals(cache.upserts.length, 1);
  assertEquals(cache.upserts[0].source, "unknown");
});

Deno.test("both rate-limited: 429 with retry_after, nothing cached", async () => {
  const { deps, cache } = makeDeps({
    "lichess.org": () =>
      new Response("{}", { status: 429, headers: { "Retry-After": "120" } }),
    "chess-api.com": () => new Response("{}", { status: 429 }),
  });

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 429);
  assertEquals(r.body.error, "rate_limited");
  assertEquals(r.body.retry_after_seconds, 120);
  assertEquals(cache.upserts.length, 0);
});

Deno.test("both 5xx: 502, nothing cached", async () => {
  const { deps, cache } = makeDeps({
    "lichess.org": () => jsonResponse(503, {}),
    "chess-api.com": () => jsonResponse(500, {}),
  });

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.status, 502);
  assertEquals(r.body, { error: "upstream_unavailable" });
  assertEquals(cache.upserts.length, 0);
});

Deno.test("malformed FEN: 400 invalid_fen, nothing touched", async () => {
  const { deps, cache, calls } = makeDeps({});

  for (const bad of ["not a fen", "", 42, null, undefined]) {
    const r = await evaluate(deps, bad);
    assertEquals(r.status, 400);
    assertEquals(r.body, { error: "invalid_fen" });
  }
  assertEquals(cache.upserts.length, 0);
  assertEquals(calls.length, 0);
});

Deno.test("mate mapping survives the chain end-to-end (Black mates)", async () => {
  const { deps, cache } = makeDeps({
    "lichess.org": () =>
      jsonResponse(200, { depth: 245, pvs: [{ moves: "d8h4", mate: -1 }] }),
  });

  const r = await evaluate(deps, START_FEN);

  assertEquals(r.body.mate, -1);
  assertEquals(r.body.eval_cp, null);
  assertEquals(cache.upserts[0].mate, -1);
  assertEquals(cache.upserts[0].eval_cp, null);
});

Deno.test("counter-differing FENs share one cache key (normalization)", async () => {
  const cache = new FakeCache();
  const first = makeDeps({ "lichess.org": LICHESS_HIT }, cache);
  await evaluate(first.deps, MIDGAME_FEN);

  assertEquals(cache.upserts.length, 1);
  assertEquals(cache.upserts[0].fen, MIDGAME_FEN_NORM);

  // Same position, different counters — must hit the cache, no fetch.
  const second = makeDeps({}, cache);
  const r = await evaluate(
    second.deps,
    MIDGAME_FEN.replace(" 4 3", " 11 22"),
  );

  assertEquals(r.body.source, "lichess");
  assertEquals(r.body.cached, true);
  assertEquals(second.calls.length, 0);
});

Deno.test("upstream call uses the normalized FEN", async () => {
  const { deps, calls } = makeDeps({ "lichess.org": LICHESS_HIT });

  await evaluate(deps, MIDGAME_FEN);

  assert(calls[0].includes(encodeURIComponent(MIDGAME_FEN_NORM)));
});
