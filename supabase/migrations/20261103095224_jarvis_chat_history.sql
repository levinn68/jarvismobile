-- JARVIS chat history baseline schema.
-- Safe to apply to a fresh Supabase project. Existing projects should use migration history.
create extension if not exists pgcrypto;

create table if not exists public.jarvis_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null default 'Percakapan baru',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.jarvis_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.jarvis_conversations(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  role text not null check (role in ('user', 'assistant')),
  content text not null,
  created_at timestamptz not null default now()
);

create index if not exists jarvis_conversations_user_updated_idx
  on public.jarvis_conversations(user_id, updated_at desc);
create index if not exists jarvis_messages_conversation_created_idx
  on public.jarvis_messages(conversation_id, created_at asc);
create index if not exists jarvis_messages_user_created_idx
  on public.jarvis_messages(user_id, created_at desc);