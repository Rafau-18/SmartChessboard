// Structural FEN validation + cache-key normalization (contract §3.3).
// Validation checks shape only (ranks/pieces/side/castling/en passant);
// position legality stays the providers' problem.

export function validateFen(fen: string): boolean {
  const fields = fen.trim().split(/\s+/);
  if (fields.length !== 6) return false;
  const [placement, side, castling, ep, halfmove, fullmove] = fields;

  const ranks = placement.split("/");
  if (ranks.length !== 8) return false;
  for (const rank of ranks) {
    let squares = 0;
    let prevDigit = false;
    for (const ch of rank) {
      if (ch >= "1" && ch <= "8") {
        if (prevDigit) return false;
        squares += Number(ch);
        prevDigit = true;
      } else if ("pnbrqkPNBRQK".includes(ch)) {
        squares += 1;
        prevDigit = false;
      } else {
        return false;
      }
    }
    if (squares !== 8) return false;
  }

  if (side !== "w" && side !== "b") return false;
  if (
    castling !== "-" && !(castling.length > 0 && /^K?Q?k?q?$/.test(castling))
  ) return false;
  if (!/^(-|[a-h][36])$/.test(ep)) return false;
  if (!/^\d+$/.test(halfmove)) return false;
  if (!/^[1-9]\d*$/.test(fullmove)) return false;
  return true;
}

// The function is the single normalization authority (contract §2.3): the
// halfmove/fullmove counters are zeroed so identical positions reached at
// different move numbers share one cache row and one upstream query.
export function normalizeFen(fen: string): string {
  const fields = fen.trim().split(/\s+/);
  return [...fields.slice(0, 4), "0", "1"].join(" ");
}
