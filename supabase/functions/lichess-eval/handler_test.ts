import { assertEquals } from "@std/assert";
import { makeHandler } from "./handler.ts";
import { FakeCache, LICHESS_HIT, makeDeps, START_FEN } from "./test-helpers.ts";

function post(body: BodyInit | null): Request {
  return new Request("http://localhost/lichess-eval", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });
}

Deno.test("OPTIONS preflight: 204 with CORS headers", async () => {
  const handler = makeHandler(makeDeps({}).deps);

  const res = await handler(
    new Request("http://localhost/lichess-eval", { method: "OPTIONS" }),
  );

  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Access-Control-Allow-Origin"), "*");
  assertEquals(
    res.headers.get("Access-Control-Allow-Headers"),
    "authorization, x-client-info, apikey, content-type",
  );
  assertEquals(
    res.headers.get("Access-Control-Allow-Methods"),
    "POST, OPTIONS",
  );
});

Deno.test("every response carries Access-Control-Allow-Origin", async () => {
  const handler = makeHandler(makeDeps({ "lichess.org": LICHESS_HIT }).deps);

  const ok = await handler(post(JSON.stringify({ fen: START_FEN })));
  assertEquals(ok.status, 200);
  assertEquals(ok.headers.get("Access-Control-Allow-Origin"), "*");
  assertEquals((await ok.json()).source, "lichess");

  const bad = await handler(post(JSON.stringify({ fen: "garbage" })));
  assertEquals(bad.status, 400);
  assertEquals(bad.headers.get("Access-Control-Allow-Origin"), "*");
});

Deno.test("malformed JSON body: 400 invalid_fen", async () => {
  const handler = makeHandler(makeDeps({}).deps);

  const res = await handler(post("{not json"));

  assertEquals(res.status, 400);
  assertEquals(await res.json(), { error: "invalid_fen" });
});

Deno.test("missing fen field: 400 invalid_fen", async () => {
  const handler = makeHandler(makeDeps({}).deps);

  const res = await handler(post(JSON.stringify({})));

  assertEquals(res.status, 400);
  assertEquals(await res.json(), { error: "invalid_fen" });
});

Deno.test("non-POST non-OPTIONS: 405", async () => {
  const handler = makeHandler(makeDeps({}).deps);

  const res = await handler(
    new Request("http://localhost/lichess-eval", { method: "GET" }),
  );

  assertEquals(res.status, 405);
});

Deno.test("cache failure surfaces as 500 internal, with CORS", async () => {
  const cache = new FakeCache();
  cache.get = () => Promise.reject(new Error("db down"));
  const handler = makeHandler(makeDeps({}, cache).deps);

  const res = await handler(post(JSON.stringify({ fen: START_FEN })));

  assertEquals(res.status, 500);
  assertEquals(await res.json(), { error: "internal" });
  assertEquals(res.headers.get("Access-Control-Allow-Origin"), "*");
});
