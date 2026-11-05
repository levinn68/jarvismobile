# Google Sign-In setup — J.A.R.V.I.S. Android

This guide records the exact OAuth identity of the CI debug APK built from commit fb16806434202b2cd1dd6a1391f45624dabc3e53.

## APK identity

- Android application ID: com.jarvis.mobile.assistant
- Android OAuth application type: Android
- SHA-1 certificate fingerprint for this exact APK: 84:6A:51:F2:20:48:21:E4:82:BF:35:F0:D3:86:FE:DB:68:EA:32:26
- GitHub Actions run: https://github.com/levinn68/jarvismobile/actions/runs/42071610628
- APK artifact ID: 13436547105
- Artifact SHA-256: 83358c1cf2b651aeb1c5c1b9ec90bca192cf0808079a6013995b14506712b113

## Google Cloud Console

1. Open Google Auth Platform → Clients.
2. Confirm there is an OAuth client of type **Web application**. Its client ID is the value used by Credential Manager as the server client ID and the GitHub Actions secret GOOGLE_WEB_CLIENT_ID.
3. Create or update an OAuth client of type **Android**:
   - Package name: com.jarvis.mobile.assistant
   - SHA-1: 84:6A:51:F2:20:48:21:E4:82:BF:35:F0:D3:86:FE:DB:68:EA:32:26
4. Ensure the Web and Android OAuth clients belong to the same Google Cloud project.
5. Ensure the OAuth consent screen allows the Google accounts used for testing.

## Supabase Dashboard

1. Open Authentication → Sign In / Providers → Google.
2. Enable Google.
3. Put the Web client ID first in the Client IDs field, then include the Android client ID. Separate multiple IDs with commas if the dashboard presents a single text field.
4. Confirm the Google client secret belongs to the same Web OAuth client.
5. Save the provider settings.

Supabase's native Android flow uses the Web client ID in Credential Manager while the Android client is registered for the Android package and signing certificate. The configured Supabase provider must accept the corresponding client IDs.

## GitHub Actions

The workflow reads GOOGLE_WEB_CLIENT_ID from repository Actions secrets. The CI preflight has confirmed that the secret exists and matches the expected Google OAuth client-ID format. The secret value is intentionally not printed. A format match does not prove it is the correct Web client from the same Google Cloud project as the Android client.

## Important signing note

This SHA-1 belongs only to the exact CI debug APK listed above. GitHub-hosted runners create a fresh debug signing key, so a later debug APK can have a different SHA-1. If you rebuild and install a newer CI debug APK, register the SHA-1 printed by that build. For a stable production identity, configure a persistent release signing keystore and register its SHA-1 as a separate Android OAuth client.

## Code-side changes

The native sign-in flow generates a cryptographically random nonce, sends its SHA-256 hash to Google, and sends the raw nonce together with the ID token to Supabase Auth. Supabase error responses now surface the message and error code so an OAuth audience/configuration failure is easier to diagnose.

A successful Android build does not prove that Google Cloud and Supabase dashboard settings are correct. The final test must be performed by installing this exact APK on the target phone and completing a real Google sign-in.
