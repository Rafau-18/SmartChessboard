-- Widening migration bringing position_evals up to contract-surfaces.md §2.3
-- (as amended 2026-06-12, S-03 Phase 1):
--   1. source CHECK gains 'chess-api' — the Lichess-miss fallback provider
--      decided 2026-06-10.
--   2. New nullable mate column — forced-mate distance in moves, White-POV
--      signed (negative = Black mates); NULL when not a forced mate.
-- Append-only follow-up to 20260531233302_position_evals.sql; existing rows
-- stay valid (additive column, widened constraint).

alter table public.position_evals
  drop constraint position_evals_source_check;

alter table public.position_evals
  add constraint position_evals_source_check
  check (source in ('lichess', 'chess-api', 'unknown'));

alter table public.position_evals
  add column mate integer;
