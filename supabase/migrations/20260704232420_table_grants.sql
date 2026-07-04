-- Explicit table privileges (the GRANT layer — separate from and beneath RLS).
--
-- Why: a database created purely from these migrations (CI pgTAP job, any fresh
-- `supabase db start`) has no privileges for the API roles on our tables, so an
-- RLS-scoped role hits "permission denied for table games" before RLS policies
-- even evaluate. Supabase Cloud (and long-lived local stacks) get equivalent
-- grants from the platform's default privileges, so this migration is a no-op
-- there — it makes the privileges explicit and reproducible instead of implied.
--
-- Least privilege, mirroring the RLS policies:
--   games:          authenticated CRUD (guarded by the *_own policies)
--   position_evals: authenticated read-only; writes happen in the lichess-eval
--                   Edge Function as service_role
-- anon gets nothing — every app surface requires a signed-in user.

grant usage on schema public to authenticated, service_role;

grant select, insert, update, delete on table public.games to authenticated;
grant all on table public.games to service_role;

grant select on table public.position_evals to authenticated;
grant all on table public.position_evals to service_role;
