import { assert, assertEquals, assertFalse } from "@std/assert";
import { normalizeFen, validateFen } from "./fen.ts";
import { MIDGAME_FEN, MIDGAME_FEN_NORM, START_FEN } from "./test-helpers.ts";

Deno.test("validateFen accepts the start position", () => {
  assert(validateFen(START_FEN));
});

Deno.test("validateFen accepts en passant and partial castling fields", () => {
  assert(
    validateFen(
      "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2",
    ),
  );
  assert(validateFen("8/8/8/8/8/8/8/K6k w - - 12 40"));
  assert(validateFen("8/8/8/8/8/8/8/K6k b Kq - 0 1"));
});

Deno.test("validateFen rejects malformed input", () => {
  assertFalse(validateFen(""));
  assertFalse(validateFen("not a fen"));
  // 7 ranks
  assertFalse(validateFen("8/8/8/8/8/8/K6k w - - 0 1"));
  // rank does not sum to 8
  assertFalse(validateFen("9/8/8/8/8/8/8/K6k w - - 0 1"));
  assertFalse(
    validateFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w KQkq - 0 1"),
  );
  // consecutive digits in a rank
  assertFalse(validateFen("44/8/8/8/8/8/8/K6k w - - 0 1"));
  // bad piece letter
  assertFalse(
    validateFen("rnbqkbnx/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
  );
  // bad side to move
  assertFalse(validateFen("8/8/8/8/8/8/8/K6k x - - 0 1"));
  // bad castling
  assertFalse(validateFen("8/8/8/8/8/8/8/K6k w KK - 0 1"));
  // bad en passant square (rank 4 is never an ep target)
  assertFalse(validateFen("8/8/8/8/8/8/8/K6k w - e4 0 1"));
  // bad counters
  assertFalse(validateFen("8/8/8/8/8/8/8/K6k w - - x 1"));
  assertFalse(validateFen("8/8/8/8/8/8/8/K6k w - - 0 0"));
  // missing fields
  assertFalse(validateFen("8/8/8/8/8/8/8/K6k w - -"));
});

Deno.test("normalizeFen zeroes the counters and keeps the rest", () => {
  assertEquals(normalizeFen(MIDGAME_FEN), MIDGAME_FEN_NORM);
  assertEquals(normalizeFen(START_FEN), START_FEN);
});

Deno.test("normalizeFen collapses counter-only differences to one key", () => {
  const a = "8/8/8/8/8/8/8/K6k w - - 3 17";
  const b = "8/8/8/8/8/8/8/K6k w - - 41 99";
  assertEquals(normalizeFen(a), normalizeFen(b));
});
