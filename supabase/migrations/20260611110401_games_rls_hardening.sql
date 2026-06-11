-- Hardening follow-up to 20260611103324_games (S-01 phase-1 implementation review).
-- Mirrors docs/reference/contract-surfaces.md §2.4/§2.6 as amended 2026-06-11:
-- policies scoped to authenticated (were PUBLIC by default); set_updated_at()
-- pins an empty search_path (Supabase linter 0011_function_search_path_mutable).

alter policy "games_select_own" on public.games to authenticated;
alter policy "games_insert_own" on public.games to authenticated;
alter policy "games_update_own" on public.games to authenticated;
alter policy "games_delete_own" on public.games to authenticated;

create or replace function public.set_updated_at()
  returns trigger language plpgsql
  set search_path = '' as $$
begin new.updated_at = now(); return new; end;
$$;
