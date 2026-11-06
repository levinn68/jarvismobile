# Supabase Security & Schema Notes (Batch 4)

## 1. JARVIS Chat History Isolation & Hardening

The JARVIS chat tables `public.jarvis_conversations` and `public.jarvis_messages` are protected by strict schema constraints, least-privilege triggers, and granular Row Level Security (`ENABLE` + `FORCE ROW LEVEL SECURITY`):

- **Anonymous Access Blocked**: `REVOKE ALL ON TABLE ... FROM PUBLIC, anon` ensures unauthenticated callers cannot query or mutate chat tables.
- **Granular Per-Operation RLS Policies**: Separate `SELECT`, `INSERT`, `UPDATE`, and `DELETE` policies validate `(select auth.uid()) is not null and (select auth.uid()) = user_id`, and verify parent conversation ownership on `public.jarvis_messages`.
- **Composite Foreign Key Integrity**: `jarvis_messages(conversation_id, user_id)` references `jarvis_conversations(id, user_id) ON DELETE CASCADE`, guaranteeing at the database engine level that a message cannot reference a conversation owned by a different user.
- **Immutable Ownership Columns**: Trigger `public.jarvis_enforce_immutable_columns()` (`SECURITY INVOKER`, `search_path = pg_catalog, public`) prevents `UPDATE` statements from modifying `id`, `user_id`, `conversation_id`, `client_id`, or `created_at`.
- **Deterministic Batch Message Ordering**: Trigger `public.jarvis_prepare_message_insert()` and identity column `sequence_number` prevent timestamp ties when `(user, assistant)` message pairs are inserted in a single PostgREST batch transaction.

## 2. Levpay Cleanup & Generic Payment Table Quarantine

Levpay has been decommissioned. Migration `20261105161000_jarvis_b4_levpay_cleanup.sql` performs the following actions when applied to the Supabase database:

1. **Confirmed Levpay Objects Removed (`DROP ... CASCADE`)**:
   - `public.levpay_paid`
   - `public.levpay_paidlog`
   - Any view, trigger, or routine in `public` prefixed with `levpay_`.
2. **Ambiguous Generic Tables Quarantined (Not Dropped Blindly)**:
   - Three tables previously observed in the shared Supabase project (`public.paid_transactions`, `public.paid_tx`, and `public.paid_logs`) have generic names whose exclusive ownership cannot be verified from the JARVIS repository source code alone.
   - To eliminate public PostgREST Data API exposure without risking accidental data loss for another service, migration `20261105161000_jarvis_b4_levpay_cleanup.sql` enables and forces Row Level Security (`ENABLE` + `FORCE ROW LEVEL SECURITY`) and revokes `PUBLIC`, `anon`, and `authenticated` privileges on those tables if they exist.
   - Once the database administrator confirms that `public.paid_transactions`, `public.paid_tx`, and `public.paid_logs` are not used by any external project, they can be permanently dropped with:
     ```sql
     drop table if exists public.paid_logs cascade;
     drop table if exists public.paid_tx cascade;
     drop table if exists public.paid_transactions cascade;
     ```