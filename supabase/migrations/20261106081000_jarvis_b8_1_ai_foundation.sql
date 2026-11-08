-- =============================================================================
-- Migration: 20261106081000_jarvis_b8_1_ai_foundation.sql
-- Purpose:
--   Batch 8.1 AI Foundation schema extension:
--   - public.jarvis_touch_b8_updated_at(): SECURITY INVOKER updated_at trigger function
--   - public.jarvis_ai_providers: user-configured provider states
--   - public.jarvis_ai_accounts: multi-account metadata & token quota tracking
--   - public.jarvis_model_preferences: active tier (CORE/PULSE/SPARK/APEX) & custom models
--   Preserves all existing B7 tables, triggers, and RLS policies untouched.
-- =============================================================================

begin;

create or replace function public.jarvis_touch_b8_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
begin
  new.updated_at := clock_timestamp();
  return new;
end;
$$;

create table if not exists public.jarvis_ai_providers (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  provider_id text not null check (char_length(btrim(provider_id)) between 2 and 40),
  enabled boolean not null default true,
  integration_state text not null default 'configured_unverified'
    check (integration_state in ('working_live', 'configured_unverified', 'ui_configured_only', 'connection_failed')),
  default_model_id text not null default 'gpt-4o',
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  unique (user_id, provider_id)
);

create table if not exists public.jarvis_ai_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  provider_id text not null check (char_length(btrim(provider_id)) between 2 and 40),
  account_label text not null check (char_length(btrim(account_label)) between 1 and 80),
  email_hint text not null default '',
  key_fingerprint text not null default 'sk***',
  enabled boolean not null default true,
  is_default boolean not null default false,
  quota_used_tokens bigint not null default 0 check (quota_used_tokens >= 0),
  quota_limit_tokens bigint not null default 200000 check (quota_limit_tokens > 0),
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp()
);

create table if not exists public.jarvis_model_preferences (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  active_tier text not null default 'APEX' check (active_tier in ('CORE', 'PULSE', 'SPARK', 'APEX')),
  active_model_id text not null default 'gpt-4o',
  custom_models jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default clock_timestamp()
);

create index if not exists jarvis_ai_accounts_user_provider_idx
  on public.jarvis_ai_accounts(user_id, provider_id, is_default desc);

alter table public.jarvis_ai_providers enable row level security;
alter table public.jarvis_ai_providers force row level security;

alter table public.jarvis_ai_accounts enable row level security;
alter table public.jarvis_ai_accounts force row level security;

alter table public.jarvis_model_preferences enable row level security;
alter table public.jarvis_model_preferences force row level security;

revoke all on table public.jarvis_ai_providers from public, anon;
revoke all on table public.jarvis_ai_accounts from public, anon;
revoke all on table public.jarvis_model_preferences from public, anon;

grant select, insert, update, delete on table public.jarvis_ai_providers to authenticated;
grant select, insert, update, delete on table public.jarvis_ai_accounts to authenticated;
grant select, insert, update, delete on table public.jarvis_model_preferences to authenticated;

drop policy if exists "jarvis_ai_providers_own" on public.jarvis_ai_providers;
create policy "jarvis_ai_providers_own"
on public.jarvis_ai_providers
for all
to authenticated
using ((select auth.uid()) is not null and (select auth.uid()) = user_id)
with check ((select auth.uid()) is not null and (select auth.uid()) = user_id);

drop policy if exists "jarvis_ai_accounts_own" on public.jarvis_ai_accounts;
create policy "jarvis_ai_accounts_own"
on public.jarvis_ai_accounts
for all
to authenticated
using ((select auth.uid()) is not null and (select auth.uid()) = user_id)
with check ((select auth.uid()) is not null and (select auth.uid()) = user_id);

drop policy if exists "jarvis_model_preferences_own" on public.jarvis_model_preferences;
create policy "jarvis_model_preferences_own"
on public.jarvis_model_preferences
for all
to authenticated
using ((select auth.uid()) is not null and (select auth.uid()) = user_id)
with check ((select auth.uid()) is not null and (select auth.uid()) = user_id);

drop trigger if exists trg_jarvis_ai_providers_updated_at on public.jarvis_ai_providers;
create trigger trg_jarvis_ai_providers_updated_at
  before update on public.jarvis_ai_providers
  for each row execute function public.jarvis_touch_b8_updated_at();

drop trigger if exists trg_jarvis_ai_accounts_updated_at on public.jarvis_ai_accounts;
create trigger trg_jarvis_ai_accounts_updated_at
  before update on public.jarvis_ai_accounts
  for each row execute function public.jarvis_touch_b8_updated_at();

drop trigger if exists trg_jarvis_model_preferences_updated_at on public.jarvis_model_preferences;
create trigger trg_jarvis_model_preferences_updated_at
  before update on public.jarvis_model_preferences
  for each row execute function public.jarvis_touch_b8_updated_at();

commit;
