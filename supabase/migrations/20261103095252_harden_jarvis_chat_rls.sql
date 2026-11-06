-- JARVIS chat data is visible only to its authenticated owner.
alter table public.jarvis_conversations enable row level security;
alter table public.jarvis_messages enable row level security;

drop policy if exists "Users manage their own JARVIS conversations" on public.jarvis_conversations;
create policy "Users manage their own JARVIS conversations"
on public.jarvis_conversations
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "Users manage their own JARVIS messages" on public.jarvis_messages;
create policy "Users manage their own JARVIS messages"
on public.jarvis_messages
for all
to authenticated
using ((select auth.uid()) = user_id)
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1 from public.jarvis_conversations c
    where c.id = jarvis_messages.conversation_id
      and c.user_id = (select auth.uid())
  )
);

grant select, insert, update, delete on public.jarvis_conversations to authenticated;
grant select, insert, update, delete on public.jarvis_messages to authenticated;