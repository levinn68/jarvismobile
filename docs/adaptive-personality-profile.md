# Adaptive Personality Profile

JARVIS retains its existing Personality Engine and adds an opt-in, per-account preference profile.

## API

All endpoints require a valid Supabase access token in `Authorization: Bearer <token>`.

- `GET /v1/personality-profile`: read the authenticated user's profile.
- `PUT /v1/personality-profile`: merge allowed preferences into the profile.
- `DELETE /v1/personality-profile`: reset the profile by deleting the saved override.

Example PUT body:

```json
{
  "language": "id",
  "tone": "casual",
  "humor": "subtle",
  "verbosity": "balanced",
  "addressStyle": "bos",
  "empathy": "high"
}
```

Allowed values: language `auto|id|en`; tone `balanced|casual|formal|technical`; humor `off|subtle|playful`; verbosity `concise|balanced|detailed`; addressStyle `auto|none|bos|boss|sir|tuan`; empathy `standard|high`.

Unknown keys and unsupported values are ignored. Updates merge with existing preferences. Data is stored under a user-scoped key in the existing `DEVICE_REGISTRY` KV namespace. No new Supabase table or secret is required.

## Chat integration

When `POST /v1/chat` includes a Supabase bearer token, the Worker validates it and loads only that account's profile. Requests without a bearer token retain legacy chat behavior and use the base persona. Invalid supplied bearer tokens are rejected.

Preferences are user-approved presentation settings, not inferred sensitive traits. They cannot override safety, accuracy, permissions, or the latest user instruction.

## Verification

Run `npm test --prefix worker`. Tests cover normalization, invalid-value rejection, merge behavior, prompt generation, per-user isolation, persistence, and reset.

Profile changes are available on the feature branch and are not deployed until the reviewed change is merged and the Worker deployment workflow completes.
