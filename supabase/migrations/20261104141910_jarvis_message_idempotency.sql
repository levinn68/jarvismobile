-- Give every client-created message a stable idempotency key.
-- This makes offline replay safe when the server accepted an insert but the
-- client lost the response and retries the same turn.
alter table public.jarvis_messages
  add column if not exists client_id uuid;

update public.jarvis_messages
set client_id = gen_random_uuid()
where client_id is null;

alter table public.jarvis_messages
  alter column client_id set default gen_random_uuid(),
  alter column client_id set not null;

create unique index if not exists jarvis_messages_client_id_uidx
  on public.jarvis_messages(client_id);