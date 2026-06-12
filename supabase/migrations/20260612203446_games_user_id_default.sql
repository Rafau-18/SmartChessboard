-- S-04 write path: let authenticated INSERTs omit user_id (contract §3.2 — "mobile does not
-- pass user_id explicitly on any write"). RLS is untouched: games_insert_own
-- (with check auth.uid() = user_id) still proves ownership of the defaulted value.

alter table public.games alter column user_id set default auth.uid();
