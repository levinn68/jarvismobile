# JARVIS Google Sign-In and Chat Sync Setup

## Supabase project
Android uses the connected Supabase project in the Singapore region.
The app must use only the public anon/publishable key. Never place a Supabase service-role key in Android or GitHub build variables.

The chat tables are:
- `public.jarvis_conversations`
- `public.jarvis_messages`

Both tables have Row Level Security. Policies restrict rows to the authenticated owner. Do not disable RLS.

## Enable Google provider
1. Open Supabase Dashboard → Authentication → Sign In / Providers → Google.
2. Enable Google and enter the Google OAuth Web Client ID and Client Secret.
3. In Google Cloud Console, configure the OAuth consent screen and create a Web application OAuth client.
4. Create an Android OAuth client using package name `com.jarvis.mobile.assistant` and the SHA-1 certificate fingerprint used to sign the installed APK.
5. Use the Web Client ID as `GOOGLE_WEB_CLIENT_ID` in the Android build. It is an OAuth client identifier, not a client secret.

## GitHub Actions
Add repository secrets `GOOGLE_WEB_CLIENT_ID` and `SUPABASE_ANON_KEY` in Settings → Secrets and variables → Actions.
The Android workflow reads both values during APK builds. The Supabase key must be the public anon/publishable key, never the service-role key.

For local builds, export both variables before running `gradle assembleDebug`.

## Existing database security issue
The tables `paid_transactions`, `levpay_paid`, `paid_tx`, `levpay_paidlog`, and `paid_logs` were found with RLS disabled. They are unrelated to JARVIS chat history and were not modified. Review their intended access model and add suitable RLS policies before enabling RLS; enabling it without policies can break existing integrations.
