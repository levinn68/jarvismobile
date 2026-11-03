-- JARVIS per-user conversation history.
-- Apply only after verifying the existing Supabase schema has no equivalent tables.
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
create index if not exists jarvis_messages_user_idx
    on public.jarvis_messages(user_id);

alter table public.jarvis_conversations enable row level security;
alter table public.jarvis_messages enable row level security;

drop policy if exists "Users manage their own JARVIS conversations" on public.jarvis_conversations;
create policy "Users manage their own JARVIS conversations"
    on public.jarvis_conversations for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Users manage their own JARVIS messages" on public.jarvis_messages;
create policy "Users manage their own JARVIS messages"
    on public.jarvis_messages for all
    using (auth.uid() = user_id)
    with check (
        auth.uid() = user_id
        and exists (
            select 1 from public.jarvis_conversations c
            where c.id = conversation_id and c.user_id = auth.uid()
        )
    );

create or replace function public.jarvis_touch_conversation()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.jarvis_conversations
       set updated_at = now()
     where id = new.conversation_id and user_id = auth.uid();
    return new;
end;
$$;

drop trigger if exists jarvis_messages_touch_conversation on public.jarvis_messages;
create trigger jarvis_messages_touch_conversation
after insert on public.jarvis_messages
for each row execute function public.jarvis_touch_conversation();

grant select, insert, update, delete on public.jarvis_conversations to authenticated;
grant select, insert, delete on public.jarvis_messages to authenticated;
