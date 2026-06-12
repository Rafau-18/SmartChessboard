// Shared fakes for the egress-free suite (supabase/AGENTS.md convention):
// fetch and the cache client are injected, no real provider is ever called.

import type { CacheRow, Deps, EvalCache } from "./eval-chain.ts";
import type { FetchLike } from "./providers.ts";

export class FakeCache implements EvalCache {
  rows = new Map<string, CacheRow>();
  upserts: CacheRow[] = [];

  get(fen: string): Promise<CacheRow | null> {
    return Promise.resolve(this.rows.get(fen) ?? null);
  }

  upsert(row: CacheRow): Promise<void> {
    this.upserts.push(row);
    this.rows.set(row.fen, row);
    return Promise.resolve();
  }
}

export interface FakeFetch {
  fetch: FetchLike;
  calls: string[];
}

// Routes requests by URL substring; throws on anything unrouted so a test
// can never silently hit a provider it did not stub.
export function fakeFetch(
  routes: Record<string, () => Response>,
): FakeFetch {
  const calls: string[] = [];
  return {
    calls,
    fetch: (url: string) => {
      calls.push(url);
      for (const [needle, respond] of Object.entries(routes)) {
        if (url.includes(needle)) return Promise.resolve(respond());
      }
      return Promise.reject(new Error(`unrouted fetch in test: ${url}`));
    },
  };
}

export const NOW = new Date("2026-06-12T12:00:00Z");

export function makeDeps(
  routes: Record<string, () => Response>,
  cache = new FakeCache(),
  now: Date = NOW,
): { deps: Deps; cache: FakeCache; calls: string[] } {
  const { fetch, calls } = fakeFetch(routes);
  return { deps: { cache, fetch, now: () => now }, cache, calls };
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function isoHoursAgo(hours: number, from: Date = NOW): string {
  return new Date(from.getTime() - hours * 60 * 60 * 1000).toISOString();
}

export const START_FEN =
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

// A middlegame-ish FEN with non-zero counters; normalizes to ... "0 1".
export const MIDGAME_FEN =
  "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 4 3";
export const MIDGAME_FEN_NORM =
  "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 1";

export const LICHESS_HIT = () =>
  jsonResponse(200, {
    fen: START_FEN,
    knodes: 13683,
    depth: 36,
    pvs: [{ moves: "e2e4 e7e5 g1f3", cp: 22 }],
  });

export const LICHESS_MISS = () =>
  jsonResponse(404, {
    error: "No cloud evaluation available for that position",
  });

export const CHESS_API_HIT = () =>
  jsonResponse(200, {
    type: "bestmove",
    move: "d2d4",
    centipawns: "31",
    mate: null,
    depth: 18,
  });
