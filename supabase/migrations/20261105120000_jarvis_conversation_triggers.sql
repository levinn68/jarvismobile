-- Automatically keep jarvis_conversations.updated_at synchronized when messages
-- are inserted or when a conversation title is updated.
create or replace function public.jarvis_touch_conversation_updated_at()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if tg_table_name = 'jarvis_messages' then
    update public.jarvis_conversations
    set updated_at = now()
    where id = new.conversation_id
      and user_id = new.user_id;
    return new;
  elsif tg_table_name = 'jarvis_conversations' then
    new.updated_at := now();
    return new;
  end if;
  return new;
end;
$$;

drop trigger if exists jarvis_messages_touch_conversation_trg on public.jarvis_messages;
create trigger jarvis_messages_touch_conversation_trg
after insert on public.jarvis_messages
for each row
execute function public.jarvis_touch_conversation_updated_at();

drop trigger if exists jarvis_conversations_set_updated_at_trg on public.jarvis_conversations;
create trigger jarvis_conversations_set_updated_at_trg
before update of title on public.jarvis_conversations
for each row
execute function public.jarvis_touch_conversation_updated_at();