import { assertEquals } from "@std/assert";
import { queryChessApi, queryLichess } from "./providers.ts";
import { fakeFetch, jsonResponse, START_FEN } from "./test-helpers.ts";

Deno.test("lichess hit maps pvs[0] cp + first move", async () => {
  const { fetch } = fakeFetch({
    "lichess.org": () =>
      jsonResponse(200, {
        fen: START_FEN,
        depth: 36,
        pvs: [{ moves: "e2e4 e7e5 g1f3", cp: 22 }],
      }),
  });
  const r = await queryLichess(fetch, START_FEN);
  assertEquals(r, {
    kind: "eval",
    evalCp: 22,
    mate: null,
    bestMove: "e2e4",
    depth: 36,
  });
});

Deno.test("lichess mate maps White-POV sign, drops cp (Black mates)", async () => {
  const { fetch } = fakeFetch({
    "lichess.org": () =>
      jsonResponse(200, { depth: 245, pvs: [{ moves: "d8h4", mate: -1 }] }),
  });
  const r = await queryLichess(fetch, START_FEN);
  assertEquals(r, {
    kind: "eval",
    evalCp: null,
    mate: -1,
    bestMove: "d8h4",
    depth: 245,
  });
});

Deno.test("lichess sends the token as Bearer when configured", async () => {
  let seenAuth: string | null = null;
  const fetch = (_url: string, init?: RequestInit) => {
    seenAuth = (init?.headers as Record<string, string>)?.["Authorization"] ??
      null;
    return Promise.resolve(
      jsonResponse(200, { depth: 1, pvs: [{ moves: "e2e4", cp: 0 }] }),
    );
  };
  await queryLichess(fetch, START_FEN, "tok-123");
  assertEquals(seenAuth, "Bearer tok-123");
});

Deno.test("lichess 404 is no-eval", async () => {
  const { fetch } = fakeFetch({
    "lichess.org": () => jsonResponse(404, { error: "No cloud evaluation" }),
  });
  assertEquals(await queryLichess(fetch, START_FEN), { kind: "no-eval" });
});

Deno.test("lichess 429 is rate-limited with Retry-After", async () => {
  const { fetch } = fakeFetch({
    "lichess.org": () =>
      new Response("{}", { status: 429, headers: { "Retry-After": "90" } }),
  });
  assertEquals(await queryLichess(fetch, START_FEN), {
    kind: "rate-limited",
    retryAfterSeconds: 90,
  });
});

Deno.test("lichess 5xx and thrown fetch are error", async () => {
  const { fetch } = fakeFetch({
    "lichess.org": () => jsonResponse(503, {}),
  });
  assertEquals(await queryLichess(fetch, START_FEN), { kind: "error" });

  const throwing = () => Promise.reject(new Error("io"));
  assertEquals(await queryLichess(throwing, START_FEN), { kind: "error" });
});

Deno.test("chess-api hit parses string centipawns", async () => {
  const { fetch } = fakeFetch({
    "chess-api.com": () =>
      jsonResponse(200, {
        type: "bestmove",
        move: "d2d4",
        centipawns: "31",
        mate: null,
        depth: 18,
      }),
  });
  assertEquals(await queryChessApi(fetch, START_FEN), {
    kind: "eval",
    evalCp: 31,
    mate: null,
    bestMove: "d2d4",
    depth: 18,
  });
});

Deno.test("chess-api forced mate: numeric centipawns dropped, mate kept (White mates)", async () => {
  const { fetch } = fakeFetch({
    "chess-api.com": () =>
      jsonResponse(200, {
        type: "bestmove",
        move: "d1h5",
        centipawns: 10000,
        mate: "1",
        depth: 12,
      }),
  });
  assertEquals(await queryChessApi(fetch, START_FEN), {
    kind: "eval",
    evalCp: null,
    mate: 1,
    bestMove: "d1h5",
    depth: 12,
  });
});

Deno.test("chess-api Black-mates: negative mate preserved (White-POV)", async () => {
  const { fetch } = fakeFetch({
    "chess-api.com": () =>
      jsonResponse(200, {
        type: "bestmove",
        move: "d8h4",
        centipawns: -10000,
        mate: -1,
        depth: 12,
      }),
  });
  assertEquals(await queryChessApi(fetch, START_FEN), {
    kind: "eval",
    evalCp: null,
    mate: -1,
    bestMove: "d8h4",
    depth: 12,
  });
});

Deno.test("chess-api INVALID_INPUT (terminal position) is no-eval, not an error", async () => {
  const { fetch } = fakeFetch({
    "chess-api.com": () =>
      jsonResponse(200, { type: "error", error: "INVALID_INPUT" }),
  });
  assertEquals(await queryChessApi(fetch, START_FEN), { kind: "no-eval" });
});

Deno.test("chess-api 429 / 5xx / thrown fetch map like lichess", async () => {
  const limited = fakeFetch({
    "chess-api.com": () => new Response("{}", { status: 429 }),
  });
  assertEquals(await queryChessApi(limited.fetch, START_FEN), {
    kind: "rate-limited",
    retryAfterSeconds: null,
  });

  const down = fakeFetch({ "chess-api.com": () => jsonResponse(500, {}) });
  assertEquals(await queryChessApi(down.fetch, START_FEN), { kind: "error" });

  const throwing = () => Promise.reject(new Error("io"));
  assertEquals(await queryChessApi(throwing, START_FEN), { kind: "error" });
});
