// HTTP surface of the function: CORS (load-bearing for the web target,
// contract §3.3), request parsing, response shaping. JWT enforcement is
// platform-level (verify_jwt) — 401 never reaches this handler.

import { type Deps, evaluate } from "./eval-chain.ts";

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
} as const;

// Static fallback when the preflight names no headers. supabase-kt sends
// `x-region` on every functions.invoke, hence its presence here.
export const fallbackAllowHeaders =
  "authorization, x-client-info, apikey, content-type, x-region";

export function makeHandler(deps: Deps): (req: Request) => Promise<Response> {
  return async (req) => {
    if (req.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          ...corsHeaders,
          // Echo whatever the browser intends to send — client libraries add
          // their own headers (supabase-kt: x-region) and a static list goes
          // stale the moment one appears.
          "Access-Control-Allow-Headers":
            req.headers.get("Access-Control-Request-Headers") ??
              fallbackAllowHeaders,
          "Access-Control-Allow-Methods": "POST, OPTIONS",
        },
      });
    }
    if (req.method !== "POST") {
      return json(405, { error: "method_not_allowed" });
    }

    let fen: unknown;
    try {
      fen = (await req.json())?.fen;
    } catch {
      return json(400, { error: "invalid_fen" });
    }

    try {
      const { status, body } = await evaluate(deps, fen);
      return json(status, body);
    } catch (err) {
      console.error("lichess-eval internal error:", err);
      return json(500, { error: "internal" });
    }
  };
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
