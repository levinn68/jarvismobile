# JARVIS All-in-One Source Bundle

This repository is the canonical source bundle for the Android client, Cloudflare Worker API, desktop companion agents, skills, tests, and Supabase baseline migrations.

## Contents
- `app/`: Android Kotlin application and device connector.
- `worker/`: Cloudflare Worker API, AI provider routing, voice, device registry/command protocol, and tests.
- `desktop/windows/` and `desktop/unix/`: Windows/macOS/Linux companion agents.
- `supabase/`: local Supabase configuration and reproducible JARVIS chat-history migrations.
- `skills/`, `docs/`, `.github/workflows/`: assistant skills, architecture notes, and CI/deployment workflows.

## Secrets
Never put real credentials in this archive or Git. Use `.env.example` as a template. Set Worker secrets with Wrangler or the Cloudflare dashboard. Supabase service-role keys must remain server-side. Google OAuth Web Client ID/Secret must match the Google provider configuration in Supabase Auth.

## Build and test
- Android: run `./gradlew assembleDebug` (CI uses Java 17 and Android SDK 35).
- Worker: `cd worker && npm test`.
- Desktop agents: `cd desktop/windows && npm test`; `cd desktop/unix && npm test`.
- Supabase local: install Supabase CLI, run `supabase start`, then `supabase db reset`.

## Current limits
The merged Android screen-action approval dialog is a fail-closed confirmation shell. Tap/type execution and complete approval-to-queue routing are not implemented. iOS/iPadOS, Wear OS, web, and IoT currently have registry metadata only, not native agents. Do not interpret this archive as a claim that unrestricted device control is complete.
