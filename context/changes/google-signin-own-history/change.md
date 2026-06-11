---
change_id: google-signin-own-history
title: Google sign-in and own game history
roadmap_id: S-01
status: impl_reviewed
created: 2026-06-10
updated: 2026-06-11
archived_at: null
---

# Change: Google sign-in and own game history

First user-facing slice of the Smart Chessboard mobile app (roadmap S-01): Google OAuth sign-in via Supabase Auth (auto-account on first sign-in), sign-out, auth-gated UI, and the player's own private, chronological game list (empty state acceptable — games arrive in S-02/S-04). Includes the `games` table migration with RLS, and — per planning decision — wires the same auth + history surface on the web target.

- Plan: `plan.md`
- Brief: `plan-brief.md`
- Roadmap: `context/foundation/roadmap.md` → S-01
- Contracts: `docs/reference/contract-surfaces.md` §2, §3.2, §4
