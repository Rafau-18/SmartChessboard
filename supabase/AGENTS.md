# AGENTS.md

Guidance for AI coding agents working inside `supabase/`. This file is the source of truth for the backend sub-project.

## What this dir is — and what it isn't

This directory is **not** a separate backend server. **Supabase Cloud is the backend.** What lives here is the project's Supabase deployment **defined as code**, version-controlled in git:

- `config.toml` — Supabase project config (CLI-managed)
- `migrations/*.sql` — schema (`games`, `position_evals`), RLS policies, triggers *(arrives during feature work — Module 2+)*
- `functions/lichess-eval/` — the single Edge Function (Deno + TypeScript) that proxies the Lichess Cloud Eval API *(arrives during feature work — Module 2+)*

There is **no application layer**, no custom HTTP server, no bespoke API. Mobile and web call Supabase **directly**:

- **Auth** — Google OAuth via Supabase Auth (browser/system-browser redirect flow); JWT auto-attached by `supabase-kt` SDK
- **Game CRUD** — PostgREST (auto-generated REST API over Postgres), scoped per user by RLS on `auth.uid()`

## Why exactly one Edge Function (and not zero, and not three)

`lichess-eval` is the only piece of "code" backend, and it exists for two specific reasons:

1. **Server-side secret custody.** `LICHESS_TOKEN` (raises Lichess rate limit) and `SUPABASE_SERVICE_ROLE_KEY` (writes the cache) must never ship in the mobile/web client. The function holds them.
2. **Sole writer to the global eval cache.** Only this function writes `public.position_evals` (via `service_role`, bypassing RLS). Clients only read. The cache is global by FEN — sharing it across users is correct and saves Lichess calls.

If a future task feels like it wants a "second backend responsibility" here, push back. The design choice is **no bespoke server** — see [`context/foundation/tech-stack.md`](../context/foundation/tech-stack.md) "Why this stack". The right home for new logic is usually either: client-side (in `commonMain` of `SmartChessboard/`), a new RLS policy / SQL trigger / database function, or — only if neither fits — a new Edge Function with its own clear justification.

## Authoritative contracts

Edit the contract first, then mirror schema/function code. Not the other way round.

- Schema, RLS, indexes, triggers → [`docs/reference/contract-surfaces.md`](../docs/reference/contract-surfaces.md) §2
- Mobile/web ↔ Supabase API (PostgREST + `lichess-eval`) → §3
- Google OAuth flow → §4
- PGN / FEN data model conventions → §5

## Build & test (when work resumes)

| What | How |
|---|---|
| Local full stack | `supabase start` (Docker — Postgres + GoTrue + Storage + Studio); mirrors CI |
| Schema / RLS tests | `supabase test db` (pgTAP) — RLS bugs cannot be caught by mocks |
| Edge Function tests | `deno test` from `functions/lichess-eval/` — runs without real Lichess egress |
| Apply migrations to hosted | `supabase db push` (after `supabase link`) |

## Conventions

- **Migrations are append-only.** Never edit a migration that already shipped to a hosted environment — write a follow-up migration instead.
- **Secrets** (`LICHESS_TOKEN`, `SUPABASE_SERVICE_ROLE_KEY`) are set via `supabase secrets set ...` — never inline in code, never committed. The mobile/web client only sees `SUPABASE_URL` + `anon key` (both public, RLS protects).
- **Region**: EU Frankfurt (`eu-central-1`) — see tech-stack.md backend table.
- **Gitignored** (agents won't see): `.env.local`, `.temp/`.
