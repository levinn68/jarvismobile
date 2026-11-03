-- Harden JARVIS chat history policies and trigger permissions.
drop policy if exists "Users manage their own JARVIS conversations" on public.jarvis_conversations;
create policy "Users manage their own JARVIS conversations"
on public.jarvis_conversations for all
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "Users manage their own JARVIS messages" on public.jarvis_messages;
create policy "Users manage their own JARVIS messages"
on public.jarvis_messages for all
using ((select auth.uid()) = user_id)
with check (
 (select auth.uid()) = user_id
 and exists (
  select 1 from public.jarvis_conversations c
  where c.id = conversation_id and c.user_id = (select auth.uid())
 )
);

create or replace function public.jarvis_touch_conversation()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
 update public.jarvis_conversations
 set updated_at = now()
 where id = new.conversation_id and user_id = (select auth.uid());
 return new;
end;
$$;
revoke all on function public.jarvis_touch_conversation() from public, anon, authenticated;
revoke all on public.jarvis_conversations from anon;
revoke all on public.jarvis_messages from anon;
