-- Minimal domain-real schema slice for the first deploy's connectivity probe.
-- Global eval cache keyed by FEN (no user_id — cache is shared across users).
-- Matches docs/reference/contract-surfaces.md §2.3 / §2.4.

create table public.position_evals (
  fen        text primary key,
  eval_cp    integer,
  best_move  text,
  depth      integer,
  source     text not null default 'unknown' check (source in ('lichess', 'unknown')),
  fetched_at timestamptz not null default now()
);

alter table public.position_evals enable row level security;

-- Read-open to any authenticated user. No write policy: PostgREST cannot write.
-- The lichess-eval Edge Function writes via service_role, which bypasses RLS.
create policy "position_evals_select_authenticated"
  on public.position_evals
  for select
  to authenticated
  using (true);
