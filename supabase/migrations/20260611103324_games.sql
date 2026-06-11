-- Games table for the first user-facing slice (S-01): own private game history.
-- Transcribed from docs/reference/contract-surfaces.md §2.2 (columns/constraints),
-- §2.4 (RLS policies), §2.5 (index), §2.6 (updated_at trigger).

create table public.games (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users (id) on delete cascade,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  mode        text not null check (mode in ('digital', 'physical')),
  status      text not null check (status in ('in_progress', 'finished')),
  result      text check (result in ('white', 'black', 'draw')),
  pgn         text not null default '',
  white_label text not null default 'White',
  black_label text not null default 'Black'
);

-- §2.4: per-CRUD owner policies — mobile/web never pass user_id; RLS scopes rows.
alter table public.games enable row level security;

create policy "games_select_own"  on public.games for select
  using (auth.uid() = user_id);

create policy "games_insert_own"  on public.games for insert
  with check (auth.uid() = user_id);

create policy "games_update_own"  on public.games for update
  using (auth.uid() = user_id);

create policy "games_delete_own"  on public.games for delete
  using (auth.uid() = user_id);

-- §2.5: chronological own-games list (PRD FR-015).
create index games_user_created_idx on public.games (user_id, created_at desc);

-- §2.6: updated_at auto-touch on each row update.
create or replace function set_updated_at()
  returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end;
$$;

create trigger games_set_updated_at
  before update on public.games
  for each row execute function set_updated_at();
