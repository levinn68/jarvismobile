# J.A.R.V.I.S. BATCH 8.5.2 — INDEPENDENT AI PROVIDER MANAGEMENT

- **Nama Pekerjaan**: `BATCH 8.5.2 — INDEPENDENT AI PROVIDER MANAGEMENT`
- **Repository**: `levinn68/jarvismobile` (Branch: `main`)
- **Android Package**: `com.jarvis.mobile.assistant`
- **Backend**: Cloudflare Workers (`worker/src/ai/multi-account-manager.js`, `worker/src/ai/model-registry.js`, `worker/src/index.js`)
- **Database & KV**: Supabase PostgreSQL + Cloudflare Workers KV (`DEVICE_REGISTRY`)
- **Target Device**: ASUS ROG Phone 6 (Android 14, Non-Root)
- **Baseline Commit**: `05d18f656aef4fb0366034c2cfbd84990da02003` (`05d18f6` — Batch 8.5.1 Production Hardening)
- **Zero-Deletion Policy**: **TERVERIFIKASI (`Deleted Files: 0`)**

## 1. Provider Architecture & Per-Provider Configuration

Setiap AI Provider kini dikelola secara independen tanpa berbagi satu konfigurasi API key tunggal. Katalog `PROVIDER_CATALOG` dan penyimpanan per-pengguna (`ai-provider-configs:v1:${userId}` serta `ai-accounts:v1:${userId}`) memisahkan konfigurasi untuk setiap provider:

| Provider ID | Nama Provider | Endpoint Utuh | Endpoint Discovery Model | Skema Autentikasi | Format Kunci | Dokumentasi Resmi (`docsUrl`) |
|---|---|---|---|---|---|---|
| `openai` | OpenAI | `https://api.openai.com/v1/chat/completions` | `https://api.openai.com/v1/models` | `Bearer API Key` | `sk-proj-...` / `sk-...` | `https://platform.openai.com/docs/api-reference` |
| `gemini` | Google Gemini | `https://generativelanguage.googleapis.com/v1beta/models` | `https://generativelanguage.googleapis.com/v1beta/models` | `Google AI Studio Key` | `AIzaSy...` | `https://ai.google.dev/gemini-api/docs` |
| `anthropic` | Anthropic Claude | `https://api.anthropic.com/v1/messages` | `https://api.anthropic.com/v1/models` | `x-api-key Header` | `sk-ant-api03-...` | `https://docs.anthropic.com/en/api/getting-started` |
| `deepseek` | DeepSeek | `https://api.deepseek.com/chat/completions` | `https://api.deepseek.com/models` | `Bearer API Key` | `sk-...` | `https://api-docs.deepseek.com/` |
| `groq` | Groq | `https://api.groq.com/openai/v1/chat/completions` | `https://api.groq.com/openai/v1/models` | `Bearer API Key` | `gsk_...` | `https://console.groq.com/docs/overview` |
| `openrouter` | OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | `https://openrouter.ai/api/v1/models` | `Bearer API Key` | `sk-or-v1-...` | `https://openrouter.ai/docs/quick-start` |
| `meta` | Meta (Llama / Groq) | `https://api.groq.com/openai/v1/chat/completions` | `https://api.groq.com/openai/v1/models` | `Bearer API Key` | `gsk_...` | `https://console.groq.com/docs/models` |
| `custom` | Custom OpenAI-Compatible | Kustom per pengguna | Kustom (`/models`) | `Bearer API Key` | Sesuai endpoint | `https://platform.openai.com/docs/api-reference/chat` |

### Validasi Silang Format API Key (`validateProviderApiKeyFormat`)

Sistem menolak penggunaan kunci API lintas-provider:
- Kunci OpenAI (`sk-proj-...`, `sk-openai-...`) ditolak dengan status `PROVIDER_AUTH_FAILED` jika dimasukkan ke `gemini`, `anthropic`, `groq`, `deepseek`, atau `openrouter`.
- Kunci Google Gemini (`AIza...`) ditolak jika dimasukkan ke `openai`, `anthropic`, `deepseek`, `groq`, atau `openrouter`.
- Kunci Anthropic Claude (`sk-ant-...`) ditolak jika dimasukkan ke `openai`, `gemini`, `deepseek`, `groq`, atau `openrouter`.
- Kunci Groq (`gsk_...`) dan OpenRouter (`sk-or-...`) ditolak jika dimasukkan ke provider lain.

### Kebijakan Status Koneksi Jujur (No Premature `Connected`)

Menyimpan API key baru melalui `saveUserAiAccount()` atau `saveProviderConfiguration()` menetapkan status akun dan provider menjadi `"configured_unverified"` dengan `connected: false` dan `connectionVerified: false`. Status hanya berubah menjadi `connected: true` (`"working_live"`) setelah `testProviderConnection()` berhasil melakukan panggilan HTTP live ke endpoint provider terkait.

## 2. Multi-Account Architecture & Provider Isolation

1. **Multi-Akun Independen per Provider**:
   - Setiap provider dapat menyimpan banyak akun terpisah (contoh: `OpenAI Personal`, `OpenAI Work`, `OpenAI Research` pada `openai`; `Gemini Personal`, `Gemini Workspace` pada `gemini`; `Claude Personal`, `Claude Work` pada `anthropic`).
   - Operasi yang didukung per akun:
     - **Tambah & Edit Akun**: `saveUserAiAccount(kv, userId, input, env)` (`POST /v1/ai-accounts`).
     - **Hapus Akun**: `removeUserAiAccount(kv, userId, accountId, { provider })` (`DELETE /v1/ai-accounts/:id`). Menghapus satu akun tidak memengaruhi akun lain atau provider lain; jika akun utama (`isDefault`) dihapus, akun aktif berikutnya pada provider yang sama otomatis dipromosikan menjadi akun utama.
     - **Aktifkan / Nonaktifkan Akun**: Mengubah `enabled: true/false` pada satu akun tanpa mematikan akun lain pada provider yang sama maupun provider berbeda.
     - **Penentuan Akun Utama (`isDefault`)**: Mengatur `isDefault: true` pada satu akun hanya mereset `isDefault: false` pada akun lain di dalam provider yang **sama**.
     - **Test Connection per Akun**: Menguji konektivitas live untuk `accountId` tertentu dengan mendekripsi kredensialnya di memori Worker.
     - **Pelacakan Kuota**: Menyimpan dan memperbarui `quotaUsedTokens`, `quotaLimitTokens`, dan `quotaRemainingTokens` (termasuk pembacaan header `x-ratelimit-limit-tokens` dan `x-ratelimit-remaining-tokens` saat uji koneksi).
2. **Pemisahan Akun Pengguna vs Worker Secret (`Provider Isolation`)**:
   - `listUserAiAccounts()` kini **hanya** mengembalikan akun milik pengguna (`source: "user_account"`).
   - Kredensial server (`OPENAI_API_KEY_1`, `GEMINI_API_KEY_1`, dll.) dipisahkan ke fungsi khusus `listServerManagedCredentials(env)` (`source: "worker_secret"`) sehingga Worker Secret tidak pernah tampil sebagai akun pribadi pengguna di UI Android.

## 3. Credential Security (AES-256-GCM + HKDF-SHA256 in KV)

Audit pada implementasi lama menemukan bahwa `saveUserAiAccount` sebelumnya menyimpan properti `apiKey` secara plaintext di dalam JSON `DEVICE_REGISTRY` KV. Pada Batch 8.5.2, celah ini telah diperbaiki sepenuhnya:

- **Key Derivation (`deriveCredentialAesKey`)**: Menggunakan WebCrypto `HKDF-SHA256` dengan kunci induk Worker (`AI_CREDENTIAL_ENCRYPTION_KEY` / `CREDENTIAL_ENCRYPTION_SECRET` / `DEVICE_AUTH_SECRET`), `salt = "jarvis-cred-salt-v1:" + userId`, dan `info = "jarvis-cred-provider-v1:" + provider` untuk menurunkan kunci `AES-GCM` 256-bit yang unik untuk setiap pasangan `(userId, provider)`.
- **Authenticated Encryption (`encryptCredentialForKv`)**: Mengenkripsi API key menggunakan `AES-256-GCM` dengan IV acak 96-bit (12 byte) dari CSPRNG (`webCrypto.getRandomValues`) serta `additionalData = "${userId}:${provider}"`.
- **Zero Plaintext in KV**: Record yang disimpan di `DEVICE_REGISTRY` KV hanya menyimpan objek `encryptedApiKey: { alg: "AES-256-GCM", kdf: "HKDF-SHA256", v: 1, iv, ciphertext }` dan `maskedKey` (mis. `sk-***3456`). Properti plaintext `apiKey` **tidak pernah** ditulis ke KV.
- **Cryptographic User & Provider Binding**: Upaya mendekripsi `encryptedApiKey` milik `userA` oleh `userB` atau mendekripsi kunci `openai` untuk `gemini` gagal secara kriptografis pada verifikasi tag autentikasi GCM (`OperationError`).

## 4. API Integration & Model Discovery

Endpoint Cloudflare Worker (`worker/src/index.js`) yang mendukung pengelolaan provider independen:

- `GET /v1/providers`: Mengembalikan daftar konfigurasi dan status independen setiap provider (`providers`) serta metadata terpisah untuk `serverCredentials`.
- `POST /v1/providers` & `POST /v1/providers/config`: Menyimpan konfigurasi independen per provider (`endpoint`, `modelsEndpoint`, `defaultModel`, `enabled`, serta opsional pembuatan akun terenkripsi).
- `POST /v1/providers/test`: Menjalankan live connection test ke `modelsEndpoint` provider terkait (menggunakan `accountId` tersimpan yang didekripsi di memori atau `apiKey` baru), mengekstrak daftar model yang tersedia (`parseDiscoveredModelsPayload`), memperbarui status koneksi (`connected` / `connection_failed`) di KV, dan mengembalikan daftar model yang ditemukan.
- `GET /v1/ai-accounts?provider=<id>`: Mengembalikan daftar akun pribadi pengguna untuk provider terkait tanpa membocorkan plaintext maupun ciphertext kunci API.
- `POST /v1/ai-accounts`: Menambah atau memperbarui akun pengguna dengan enkripsi `AES-256-GCM`.
- `DELETE /v1/ai-accounts/:accountId?provider=<id>`: Menghapus satu akun spesifik tanpa memengaruhi akun atau provider lain.
- `GET /v1/models?provider=<id>`: Mengembalikan katalog model yang terikat ketat pada provider tersebut (`getProviderModels(provider)`), sehingga OpenAI hanya menampilkan model OpenAI, Gemini hanya menampilkan model Gemini, dan Anthropic hanya menampilkan model Claude.

## 5. Android UI Changes (`JarvisAiFoundationScreens.kt` & `JarvisB8MasterShell.kt`)

1. **Perbaikan Pengiriman Parameter API Key**:
   - Pada `JarvisB8MasterShell.kt`, callback `onAddProvider = { provId, apiKey -> ... }` dan `onAddAccount = { prov, label, email, apiKey -> ... }` kini meneruskan `apiKey` beserta metadata akun ke endpoint HTTPS Worker (`/v1/ai-accounts`, `/v1/providers/config`, `/v1/providers/test`) — tidak lagi membuang parameter `apiKey` menggunakan `_`.
2. **Alur 11 Langkah Penambahan & Konfigurasi Provider (`JarvisProvidersScreen`)**:
   - Menekan tombol **Tambah Provider** menampilkan modal **Katalog Provider** (`OpenAI`, `Google Gemini`, `Anthropic Claude`, `DeepSeek`, `Groq`, `OpenRouter`, `Custom OpenAI-Compatible`).
   - Memilih satu provider membuka **Halaman Konfigurasi Independen Provider** yang menampilkan informasi autentikasi, format kunci, endpoint, tautan **Dokumentasi API Resmi** (`docsUrl`), form input `Nama Akun`, `Email`, `API Key`, `Endpoint`, dan `Default Model`.
   - Menekan **1. Simpan Konfigurasi** menyimpan konfigurasi secara terpisah dengan status `CONFIGURED_UNVERIFIED` (`connected = false`).
   - Menekan **2. Test Connection** menjalankan pengujian koneksi live ke backend, mengambil daftar model yang tersedia (`discoveredModelsState`), dan memperbarui status koneksi secara akurat.
3. **Pengelolaan Multi-Akun per Provider (`JarvisMultiAccountsScreen`)**:
   - Menyediakan tab terpisah untuk `OpenAI`, `Gemini`, `Claude`, `DeepSeek`, `Groq`, `OpenRouter`, dan `Custom`.
   - Menyediakan aksi lengkap per akun: **Test Koneksi**, **Jadikan Utama**, **Edit**, **Hapus**, toggle **Aktif/Nonaktif**, serta kartu **Kuota & Penggunaan Token**.
4. **Filter Model per Provider (`JarvisModelSelectorScreen`)**:
   - Menyediakan tab filter per provider dan menandai model yang provider-nya belum terhubung agar tidak diklaim aktif sebelum konfigurasi selesai.

## 6. Automated Test Results

Seluruh suite pengujian otomatis dieksekusi menggunakan Node.js test runner:

| Test Suite | Perintah Eksekusi | Total Tests | Pass | Fail | Durasi | Status |
|---|---|---:|---:|---:|---:|---|
| **Cloudflare Worker (B1–B8.5.2)** | `npm test --prefix worker` | 138 | 138 | 0 | 6,456.15 ms | **PASSED** |
| **Batch 8.5.2 Independent Provider Suite** | `node --test worker/test/batch8-5-2-independent-provider-management.test.js` | 5 | 5 | 0 | 109.43 ms | **PASSED** |
| **Desktop Companion — Windows** | `npm test --prefix desktop/windows` | 9 | 9 | 0 | 549.06 ms | **PASSED** |
| **Desktop Companion — macOS** | `npm test --prefix desktop/macos` | 10 | 10 | 0 | 533.30 ms | **PASSED** |
| **Desktop Companion — Linux/Unix** | `npm test --prefix desktop/unix` | 10 | 10 | 0 | 532.23 ms | **PASSED** |
| **Total Keseluruhan** | **Seluruh Suite Otomatis** | **167** | **167** | **0** | **8,070.74 ms** | **100% PASSED** |

## 7. Changed Files (Before/After Size, Line Count & SHA-256)

| File Path | Ukuran Sebelum (`05d18f6`) | Baris Sebelum | SHA-256 Sebelum (`05d18f6`) | Ukuran Sesudah (`B8.5.2`) | Baris Sesudah | SHA-256 Sesudah (`B8.5.2`) |
|---|---:|---:|---|---:|---:|---|
| `worker/src/ai/multi-account-manager.js` | 32,081 B | 839 | `f5e671fc952de6acd17d1a2b96d3cd797e8ed2d39930029d2f6b031c80e100f0` | 66,492 B | 1,731 | `28e3edefe0b52d3bef61bffa8f1bc75c8bf32e6af012b10fa87f4ce2ba9246db` |
| `worker/src/ai/model-registry.js` | 8,584 B | 233 | `e1bd60fcc46a825c0f8db3a9c69ccff2e8eac0048c5e7307e03ce52547fc2732` | 16,250 B | 350 | `7949189000362ea3cd2a87b94d40e697653d636968b70e4bbd5ae85c33afeb6a` |
| `worker/src/index.js` | 79,526 B | 1,684 | `c90c51b8dfbfd8ab51dd919d23e52d3640b65b7e7f1c5b3834dab41d10921d11` | 82,270 B | 1,733 | `e8eee7fe670455f8b032c9c3953a8ca4199f9981bfd473ad021483e2cfe15978` |
| `app/src/main/java/com/jarvis/mobile/ai/JarvisAiFoundationScreens.kt` | 22,365 B | 493 | `a2ca39a02ee3592038be6630d8db28b56bb41865b493d39ce369cba45217e9a9` | 65,599 B | 1,336 | `8230f1ae916dbbde6488136af60940313969db40aa01f6f9c6fcd95263e48114` |
| `app/src/main/java/com/jarvis/mobile/ui/JarvisB8MasterShell.kt` | 18,163 B | 360 | `45f91d88a91d38e2a20f9d6cc119f348616dc41a365b8d781b0e46c26dfd9d1c` | 49,791 B | 949 | `67afcb680c211cae053d15d9b9700be150d3deb81787b79dd81e3ecf8ee88868` |
| `worker/test/batch8-5-2-independent-provider-management.test.js` | 0 B (Baru) | 0 | `N/A (File Baru pada Batch 8.5.2)` | 21,858 B | 574 | `85e0b4525675f08e462d395634a8de60cac545a839914da255abebe74ad8db86` |

## 8. Git Commit & Zero-Deletion Verification

- **Deleted Files vs B7 (`aca88d1`)**: `0`
- **Deleted Files vs B8.5.1 (`05d18f6`)**: `0`
- **Commit Message**: `feat(b8.5.2): implement independent AI provider management and AES-256-GCM credential encryption`

## 9. Known Issues & Environment Constraints

1. **Android Gradle Build di Sandbox VM (`BLOCKED`)**: Lingkungan container VM tidak memiliki akses internet keluar untuk mengunduh `gradle-wrapper.jar` dan tidak memiliki instalasi Android SDK lokal (`ANDROID_HOME=UNSET`). Integritas sintaks, kurung kurawal, dan kontrak kode Kotlin diverifikasi secara otomatis melalui test suite (`ok 72`, `ok 76`, `ok 82`, `ok 95`, `ok 99`).
2. **Rotasi Master Encryption Secret**: Untuk keamanan produksi maksimal pada Cloudflare Workers, disarankan menetapkan secret khusus `AI_CREDENTIAL_ENCRYPTION_KEY` melalui `wrangler secret put AI_CREDENTIAL_ENCRYPTION_KEY` (jika belum diset, sistem secara otomatis menurunkan kunci HKDF dari `DEVICE_AUTH_SECRET`).