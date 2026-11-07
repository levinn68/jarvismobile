-- =============================================================================
-- Migration: 20261105161000_jarvis_b4_levpay_cleanup.sql
-- Purpose:
--   1. Remove all confirmed obsolete Levpay database objects (public.levpay_paid,
--      public.levpay_paidlog, and any levpay_* views, triggers, or functions).
--   2. Safely quarantine generic payment tables (public.paid_transactions,
--      public.paid_tx, public.paid_logs) whose ownership cannot be 100% verified
--      from repository source code alone: enable RLS and revoke anon/authenticated
--      Data API access without blindly destroying potentially shared data.
-- =============================================================================

begin;

-- 1. Drop confirmed Levpay-specific tables and dependent triggers/policies/views
do $$
declare
  r record;
begin
  -- Drop any views prefixed with levpay_
  for r in
    select table_schema, table_name
      from information_schema.views
     where table_schema = 'public'
       and table_name like 'levpay\_%' escape '\'
  loop
    execute format('drop view if exists %I.%I cascade', r.table_schema, r.table_name);
  end loop;

  -- Drop confirmed Levpay tables (levpay_paidlog, levpay_paid, and any other levpay_* table)
  for r in
    select tablename
      from pg_tables
     where schemaname = 'public'
       and (tablename in ('levpay_paid', 'levpay_paidlog') or tablename like 'levpay\_%' escape '\')
  loop
    execute format('revoke all on table public.%I from public, anon, authenticated', r.tablename);
    execute format('drop table if exists public.%I cascade', r.tablename);
  end loop;

  -- Drop any functions/routines prefixed with levpay_
  for r in
    select p.oid::regprocedure as proc_sig
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname like 'levpay\_%' escape '\'
  loop
    execute format('drop function if exists %s cascade', r.proc_sig);
  end loop;
end;
$$;

-- 2. Quarantine generic payment tables (paid_transactions, paid_tx, paid_logs)
-- Ownership cannot be proven from JARVIS source code alone. Do NOT drop blindly;
-- instead, lock down PostgREST Data API exposure by enabling RLS and revoking
-- privileges from PUBLIC, anon, and authenticated roles.
do $$
declare
  v_table text;
begin
  foreach v_table in array array['paid_transactions', 'paid_tx', 'paid_logs']
  loop
    if to_regclass('public.' || v_table) is not null then
      execute format('alter table public.%I enable row level security', v_table);
      execute format('alter table public.%I force row level security', v_table);
      execute format('revoke all on table public.%I from public, anon, authenticated', v_table);
    end if;
  end loop;
end;
$$;

commit;
