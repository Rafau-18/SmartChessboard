// Wiring only — real cache (position_evals via service_role), real fetch,
// real clock. All logic lives in handler.ts / eval-chain.ts and is covered
// by the egress-free test suite.

import { createClient } from "@supabase/supabase-js";
import type { CacheRow, EvalCache } from "./eval-chain.ts";
import { makeHandler } from "./handler.ts";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

const cache: EvalCache = {
  async get(fen: string): Promise<CacheRow | null> {
    const { data, error } = await supabase
      .from("position_evals")
      .select("fen, eval_cp, mate, best_move, depth, source, fetched_at")
      .eq("fen", fen)
      .maybeSingle();
    if (error) throw error;
    return data as CacheRow | null;
  },
  async upsert(row: CacheRow): Promise<void> {
    const { error } = await supabase.from("position_evals").upsert(row);
    if (error) throw error;
  },
};

Deno.serve(
  makeHandler({
    cache,
    fetch: (input, init) => globalThis.fetch(input, init),
    lichessToken: Deno.env.get("LICHESS_TOKEN") ?? undefined,
    now: () => new Date(),
  }),
);
