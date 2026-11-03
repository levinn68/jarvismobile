# Supabase security notes

The JARVIS chat tables `jarvis_conversations` and `jarvis_messages` have RLS enabled with owner-scoped policies.

The connected Supabase project also contains five payment-related tables with RLS disabled:
- `public.paid_transactions`
- `public.levpay_paid`
- `public.paid_tx`
- `public.levpay_paidlog`
- `public.paid_logs`

These are not JARVIS chat tables and are intentionally not changed by the JARVIS bundle. Supabase security advisors report them as externally exposed to roles that can access the Data API. Enabling RLS without matching policies can break existing payment integrations. Review ownership and intended access, then add table-specific policies before enabling RLS. Do not copy payment data or credentials into source archives.
