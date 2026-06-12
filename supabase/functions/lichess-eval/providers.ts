// Upstream eval providers (contract §3.3): Lichess Cloud Eval, Chess-API.com.
// Each maps its raw response into one canonical ProviderResult; fetch is
// injected so tests run without egress.
//
// Mate convention (verified empirically 2026-06-12 against both providers
// with a Black-mates-in-1 position): both report mate White-POV signed —
// negative = Black mates. Stored unchanged. When mate is set, eval_cp is
// dropped (Chess-API pads it with ±10000, which is noise, not a score).

export type ProviderResult =
  | {
    kind: "eval";
    evalCp: number | null;
    mate: number | null;
    bestMove: string | null;
    depth: number | null;
  }
  | { kind: "no-eval" }
  | { kind: "rate-limited"; retryAfterSeconds: number | null }
  | { kind: "error" };

export type FetchLike = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

export async function queryLichess(
  fetchFn: FetchLike,
  fen: string,
  token?: string,
): Promise<ProviderResult> {
  try {
    const headers: Record<string, string> = {};
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const res = await fetchFn(
      `https://lichess.org/api/cloud-eval?fen=${encodeURIComponent(fen)}`,
      { headers, signal: AbortSignal.timeout(10_000) },
    );
    if (res.status === 404) return { kind: "no-eval" };
    if (res.status === 429) {
      return { kind: "rate-limited", retryAfterSeconds: retryAfter(res) };
    }
    if (!res.ok) return { kind: "error" };
    const body = await res.json();
    const pv = body?.pvs?.[0];
    if (!pv) return { kind: "no-eval" };
    const mate = typeof pv.mate === "number" ? pv.mate : null;
    const cp = typeof pv.cp === "number" ? pv.cp : null;
    const bestMove = typeof pv.moves === "string" && pv.moves.length > 0
      ? pv.moves.split(" ")[0]
      : null;
    return {
      kind: "eval",
      evalCp: mate != null ? null : cp,
      mate,
      bestMove,
      depth: typeof body.depth === "number" ? body.depth : null,
    };
  } catch {
    return { kind: "error" };
  }
}

export async function queryChessApi(
  fetchFn: FetchLike,
  fen: string,
): Promise<ProviderResult> {
  try {
    const res = await fetchFn("https://chess-api.com/v1", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fen, depth: 18 }),
      signal: AbortSignal.timeout(15_000),
    });
    if (res.status === 429) {
      return { kind: "rate-limited", retryAfterSeconds: retryAfter(res) };
    }
    if (!res.ok) return { kind: "error" };
    const body = await res.json();
    // Chess-API answers "cannot evaluate" (terminal position INVALID_INPUT,
    // strict-FEN rejections) as 200 {type:"error"} — provider-no-eval, never
    // an outage (contract §3.3).
    if (body?.type === "error" || typeof body?.move !== "string") {
      return { kind: "no-eval" };
    }
    // centipawns arrives as a JSON string for normal evals but a number
    // alongside forced mates; mate has been seen as both too. Parse both.
    const mate = parseIntish(body.mate);
    const cp = parseIntish(body.centipawns);
    return {
      kind: "eval",
      evalCp: mate != null ? null : cp,
      mate,
      bestMove: body.move,
      depth: typeof body.depth === "number" ? body.depth : null,
    };
  } catch {
    return { kind: "error" };
  }
}

function parseIntish(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number.parseInt(v, 10);
    return Number.isNaN(n) ? null : n;
  }
  return null;
}

function retryAfter(res: Response): number | null {
  const h = res.headers.get("Retry-After");
  if (!h) return null;
  const n = Number.parseInt(h, 10);
  return Number.isNaN(n) ? null : n;
}
