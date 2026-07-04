# Smart Chessboard — Supabase backend

The backend of [Smart Chessboard](../README.md), defined as code. **Supabase Cloud is
the backend** — this directory is not a server and there is no application layer.
Mobile and web clients call Supabase directly:

- **Auth** — Google OAuth via Supabase Auth (external-browser redirect flow; JWT
  attached automatically by the `supabase-kt` SDK).
- **Game CRUD** — PostgREST (auto-generated REST over Postgres), scoped per user by
  RLS on `auth.uid()`.
- **Post-game analysis** — the one Edge Function below.

## Contents

- `config.toml` — Supabase project config (CLI-managed).
- `migrations/` — append-only schema history: the `games` and `position_evals`
  tables, RLS policies, the `updated_at` trigger, and the first-sign-in trigger that
  seeds every new account with eight famous historical games.
- `functions/lichess-eval/` — the single Edge Function (Deno + TypeScript). Given a
  FEN it returns an engine evaluation: cache lookup → Lichess Cloud Eval →
  Chess-API.com (Stockfish) fallback → negative-cache `unknown`. It exists to keep
  secrets server-side and is the **sole writer** of the shared `position_evals`
  cache (clients only read).
- `tests/` — pgTAP tests for the schema, RLS policies, and the signup seed.

## Local dev & test

| What | How |
| --- | --- |
| Local full stack (Docker) | `supabase start` — Postgres + Auth + Studio |
| Schema / RLS tests (pgTAP) | `supabase test db` |
| Edge Function tests | `deno test` in `functions/lichess-eval/` (no real egress) |
| Apply migrations to hosted | `supabase db push` (after `supabase link`) |

## Conventions

- **Contracts first.** Schema and API shapes are specified in
  [`contract-surfaces.md`](../docs/reference/contract-surfaces.md) §2–§5 — edit the
  contract, then mirror it in migrations/function code, not the other way round.
- **Migrations are append-only** — never edit one that already shipped to a hosted
  environment; write a follow-up migration instead.
- **Secrets** (`LICHESS_TOKEN`, the service-role key) are set via
  `supabase secrets set` — never inline in code, never committed. Clients only ever
  see `SUPABASE_URL` + the anon key (public; RLS protects the data).
- Region: EU Frankfurt (`eu-central-1`). Gitignored: `.env.local`, `.temp/`.

Agent/contributor rules — most importantly when a *second* Edge Function would be
justified (almost never) — live in [`AGENTS.md`](AGENTS.md).
