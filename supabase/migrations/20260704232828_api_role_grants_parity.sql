-- Follow-up to 20260704232420_table_grants: anon needs the same table
-- privileges the Supabase platform grants by default.
--
-- The RLS suite asserts production semantics: anon *queries* games and gets
-- zero rows (RLS filters), rather than erroring on a missing grant. Withholding
-- grants from anon was stricter than production — and a stricter GRANT layer in
-- CI would let RLS gaps hide behind permission-denied errors. Grants mirror the
-- platform; RLS alone is the privacy boundary, identically in every environment.

grant usage on schema public to anon;
grant select, insert, update, delete on table public.games to anon;
grant select, insert, update, delete on table public.position_evals to anon;

-- Future tables/sequences created by migrations (running as postgres) get the
-- API-role grants automatically — mirrors the platform's default privileges so
-- a fresh-from-migrations database (CI pgTAP) behaves like Supabase Cloud and
-- new-table migrations don't each need to remember explicit grants.
alter default privileges for role postgres in schema public
  grant select, insert, update, delete on tables to anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  grant usage, select on sequences to anon, authenticated, service_role;
