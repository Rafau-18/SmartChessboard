# AGENTS.md

Guidance for AI coding agents working inside `supabase/`. **What this directory is, its contents, local dev/test commands, and the conventions (append-only migrations, secret handling, region) live in [`README.md`](README.md)** — read that first. This file adds only the rules that specifically bite agents.

The one-line summary: **Supabase Cloud is the backend**; this dir is the deployment defined as code (`config.toml` + `migrations/*.sql` + one Edge Function). There is no application layer — clients call Supabase directly (Auth + PostgREST under RLS).

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

## Agent rules on top of the README conventions

- **RLS bugs cannot be caught by mocks** — schema/policy changes must be verified with `supabase test db` (pgTAP), not client-side fakes.
- Gitignored here (agents won't see them): `.env.local`, `.temp/`.
