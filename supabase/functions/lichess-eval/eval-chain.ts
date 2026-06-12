// The §3.3 eval chain: cache → Lichess Cloud Eval → Chess-API.com →
// negative-cache `unknown`. All effects (cache, fetch, clock) are injected
// so the chain is provable without egress.

import { normalizeFen, validateFen } from "./fen.ts";
import {
  type FetchLike,
  type ProviderResult,
  queryChessApi,
  queryLichess,
} from "./providers.ts";

export interface CacheRow {
  fen: string;
  eval_cp: number | null;
  mate: number | null;
  best_move: string | null;
  depth: number | null;
  source: "lichess" | "chess-api" | "unknown";
  fetched_at: string;
}

export interface EvalCache {
  get(fen: string): Promise<CacheRow | null>;
  upsert(row: CacheRow): Promise<void>;
}

export interface Deps {
  cache: EvalCache;
  fetch: FetchLike;
  lichessToken?: string;
  now(): Date;
}

export interface EvalResponse {
  status: number;
  body: Record<string, unknown>;
}

const POSITIVE_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days
const UNKNOWN_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

export async function evaluate(
  deps: Deps,
  rawFen: unknown,
): Promise<EvalResponse> {
  if (typeof rawFen !== "string" || !validateFen(rawFen)) {
    return { status: 400, body: { error: "invalid_fen" } };
  }
  const fen = normalizeFen(rawFen);

  const cached = await deps.cache.get(fen);
  if (cached && isFresh(cached, deps.now())) {
    if (cached.source === "unknown") {
      return { status: 200, body: { fen, source: "unknown" } };
    }
    return {
      status: 200,
      body: {
        fen,
        eval_cp: cached.eval_cp,
        mate: cached.mate,
        best_move: cached.best_move,
        depth: cached.depth,
        // Provenance survives the cache: `source` stays the provider that
        // produced the eval; `cached` says it was served from the shared cache.
        source: cached.source,
        cached: true,
        fetched_at: cached.fetched_at,
      },
    };
  }

  const lichess = await queryLichess(deps.fetch, fen, deps.lichessToken);
  if (lichess.kind === "eval") {
    return upsertAndRespond(deps, fen, lichess, "lichess");
  }

  const chessApi = await queryChessApi(deps.fetch, fen);
  if (chessApi.kind === "eval") {
    return upsertAndRespond(deps, fen, chessApi, "chess-api");
  }

  // Neither provider produced an eval. A provider that *answered* "no eval
  // for this position" makes `unknown` the truthful, cacheable outcome; when
  // both merely errored, nothing is cached (contract §3.3).
  if (lichess.kind === "no-eval" || chessApi.kind === "no-eval") {
    await deps.cache.upsert({
      fen,
      eval_cp: null,
      mate: null,
      best_move: null,
      depth: null,
      source: "unknown",
      fetched_at: deps.now().toISOString(),
    });
    return { status: 200, body: { fen, source: "unknown" } };
  }

  const rateLimited = [lichess, chessApi].find(
    (r): r is Extract<ProviderResult, { kind: "rate-limited" }> =>
      r.kind === "rate-limited",
  );
  if (rateLimited) {
    return {
      status: 429,
      body: {
        error: "rate_limited",
        retry_after_seconds: rateLimited.retryAfterSeconds ?? 60,
      },
    };
  }
  return { status: 502, body: { error: "upstream_unavailable" } };
}

async function upsertAndRespond(
  deps: Deps,
  fen: string,
  result: Extract<ProviderResult, { kind: "eval" }>,
  source: "lichess" | "chess-api",
): Promise<EvalResponse> {
  const fetchedAt = deps.now().toISOString();
  await deps.cache.upsert({
    fen,
    eval_cp: result.evalCp,
    mate: result.mate,
    best_move: result.bestMove,
    depth: result.depth,
    source,
    fetched_at: fetchedAt,
  });
  return {
    status: 200,
    body: {
      fen,
      eval_cp: result.evalCp,
      mate: result.mate,
      best_move: result.bestMove,
      depth: result.depth,
      source,
      cached: false,
      fetched_at: fetchedAt,
    },
  };
}

function isFresh(row: CacheRow, now: Date): boolean {
  const age = now.getTime() - new Date(row.fetched_at).getTime();
  return age < (row.source === "unknown" ? UNKNOWN_TTL_MS : POSITIVE_TTL_MS);
}
