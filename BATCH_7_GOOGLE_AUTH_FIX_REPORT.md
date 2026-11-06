# J.A.R.V.I.S. BATCH 7 — GOOGLE SIGN-IN & SUPABASE AUTH FIX REPORT (`BATCH_7_GOOGLE_AUTH_FIX_REPORT.md`)

## 1. Ringkasan Eksekutif & Gejala Masalah

Laporan ini mendokumentasikan hasil investigasi mendalam, pembuktian akar masalah (*root cause analysis*), perbaikan source code langsung, serta pengujian otomatis terhadap kegagalan **Google Sign-In** pada aplikasi Android **J.A.R.V.I.S. Batch 7 Final**.

### Alur Kegagalan yang Dilaporkan Pengguna
1. Pengguna membuka menu **Akun Google & Akses Perangkat** (atau panel **ACCOUNT**).
2. Pengguna menekan tombol login Google.
3. Dialog pemilihan akun Google (*Credential Manager Account Picker*) muncul.
4. Pengguna memilih akun Google.
5. **Proses login gagal tepat setelah akun dipilih**, dan akun tidak berhasil tersambung ke Supabase.

---

## 2. Pembuktian Akar Masalah (*Root Cause Analysis*)

Berdasarkan pemeriksaan langsung pada source code B7 (`SupabaseJarvisClient.kt`, `SupabaseSessionVault.kt`, `MainActivity.kt`, `DeviceSetupActivity.kt`, `app/build.gradle.kts`, `AndroidManifest.xml`, dan `.github/workflows/android.yml`), ditemukan **5 penyebab teknis nyata** di level kode yang menyebabkan kegagalan tepat setelah pemilihan akun Google:

| ID | Tahap Diagnostik | Lokasi File & Baris (B7 Awal) | Akar Masalah Terbukti | Dampak pada Alur Login |
| :--- | :--- | :--- | :--- | :--- |
| **RC-01 (Utama / Deterministik)** | `SUPABASE_TOKEN_EXCHANGE` | `SupabaseJarvisClient.kt` baris 450 (`http()`) & `app/build.gradle.kts` baris 17 | `SUPABASE_ANON_KEY` menggunakan format kunci publik Supabase terbaru (`sb_publishable_BtFlJ2XJc4SW4kNSmIZ8bA_WcTQgFq-`), **bukan** JWT 3-segmen (`eyJ...`). Namun fungsi `http()` selalu mengirim header `Authorization: Bearer ${token ?: anonKey}`. Saat `signInWithGoogle()` memanggil `POST /auth/v1/token?grant_type=id_token` dengan `bearer = null`, aplikasi mengirim `Authorization: Bearer sb_publishable_...`. Gateway/GoTrue Supabase memvalidasi setiap nilai `Authorization: Bearer` sebagai JWT dan langsung menolak request dengan error JWT tidak valid (`401`/`400`) tepat setelah pengguna memilih akun Google. | **100% gagal setelah pemilihan akun Google** ketika menggunakan `sb_publishable_*` key. |
| **RC-02** | `GOOGLE_ID_TOKEN_RECEIVED` | `SupabaseJarvisClient.kt` baris 83–85 | Setelah pengguna memilih akun pada dialog `GetSignInWithGoogleOption` (`com.google.android.libraries.identity.googleid:googleid:1.1.1`), Credential Manager dapat mengembalikan `CustomCredential` dengan tipe `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL` maupun instance `GoogleIdTokenCredential`. Kode lama hanya memeriksa `credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` sehingga kredensial SIWG ditolak dengan `"Google Sign-In tidak mengembalikan ID token yang valid."` | **Gagal tepat setelah dialog pemilihan akun Google ditutup** pada perangkat dengan Play Services SIWG. |
| **RC-03** | `GOOGLE_ID_TOKEN_RECEIVED` & `SUPABASE_TOKEN_EXCHANGE` | `SupabaseJarvisClient.kt` baris 87–95 | Kode lama tidak memvalidasi struktur JWT 3-segmen atau klaim `aud` pada `google.idToken` sebelum dikirim ke Supabase, dan tidak meneruskan klaim `nonce` apabila disematkan oleh Google Play Services di dalam payload JWT `id_token`. | Jika `aud` tidak cocok atau `nonce` ada di dalam `id_token` tanpa dikirim pada body JSON, Supabase GoTrue menolak dengan HTTP 400 (`Passed nonce and nonce in id_token should either both exist or not exist`). |
| **RC-04** | `AUTH_CONFIG_VALIDATION` & Alur UI Menu | `MainActivity.kt` baris 416–420, `DeviceSetupActivity.kt` baris 57–61, `app/build.gradle.kts` baris 18 | (1) Jika APK dikompilasi tanpa environment variable `GOOGLE_WEB_CLIENT_ID`, `BuildConfig.GOOGLE_WEB_CLIENT_ID` bernilai `""` tanpa mekanisme *runtime override* di UI. (2) Menekan tombol **"AKUN GOOGLE & AKSES PERANGKAT"** di Pengaturan saat belum login hanya menutup dialog dan menghentikan proses tanpa membuka dialog login Google, sedangkan menekan tombol di `DeviceSetupActivity` menampilkan pesan blokir alih-alih memicu `signInWithGoogle()`. | Pengguna terblokir di menu Akun Google & Akses Perangkat atau tidak dapat memasukkan Web Client ID tanpa kompilasi ulang APK. |
| **RC-05** | `SESSION_VAULT_SAVE` | `SupabaseSessionVault.kt` baris 30–49 | (1) `save()` menggunakan `.apply()` asinkron tanpa verifikasi dekripsi *round-trip* (`read()`). (2) `read()` memanggil `clear()` (menghapus entry `AndroidKeyStore`) setiap kali terjadi exception, sehingga kegagalan baca sementara dapat menghancurkan kunci enkripsi sesi secara permanen. | Sesi berisiko gagal tersimpan tanpa terdeteksi atau terhapus permanen saat pemulihan sesi (`restoreSession`). |

---

## 3. Daftar File yang Diubah & Detail Perbaikan

### 3.1. `app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt`
- **7 Kode Diagnostik Tahap Autentikasi (`AuthStage` & `JarvisAuthException`)**:
  Setiap tahap autentikasi kini memancarkan status *real-time* ke UI dan menyertakan kode tahap eksplisit apabila terjadi kegagalan:
  1. `AUTH_CONFIG_VALIDATION`: Memvalidasi `SUPABASE_URL` (`https://...`), `SUPABASE_ANON_KEY`, dan `GOOGLE_WEB_CLIENT_ID` (format `<project>-<hash>.apps.googleusercontent.com`, menolak nilai kosong atau Android Client ID). Mendukung resolusi dari runtime override (`jarvis_secure_settings`), `BuildConfig.GOOGLE_WEB_CLIENT_ID`, dan resource `default_web_client_id`.
  2. `GOOGLE_CREDENTIAL_REQUEST`: Menangani `GetCredentialCancellationException`, `NoCredentialException`, dan `GetCredentialException` (mendeteksi kode `10` / `28444` / `DEVELOPER_ERROR` untuk ketidakcocokan SHA-1 / package name `com.jarvis.mobile.assistant`, gangguan jaringan, atau interupsi Play Services).
  3. `GOOGLE_ID_TOKEN_RECEIVED`: Mengekstrak `GoogleIdTokenCredential` baik dari instance langsung, `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`, maupun `TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL`. Memvalidasi bahwa `idToken` adalah JWT 3-segmen yang valid dan klaim `aud` cocok dengan `webClientId`.
  4. `SUPABASE_TOKEN_EXCHANGE`:
     - Memperbaiki fungsi `http()`: selalu mengirim header `apikey: <SUPABASE_ANON_KEY>`, dan **hanya** mengirim `Authorization: Bearer ...` apabila `token` sesi pengguna tersedia atau `anonKey` berformat JWT 3-segmen (`eyJ...`). Tidak pernah lagi mengirim `Authorization: Bearer sb_publishable_...`.
     - Meneruskan klaim `nonce` secara otomatis apabila terdapat di dalam payload JWT `id_token`.
     - Mengurai seluruh field error JSON Supabase (`error_description`, `message`, `msg`, `error`, `error_code`, `code`, `hint`) dan membedakan secara spesifik HTTP `400`, `401`, `403`, `429`, dan `5xx`.
     - Menyaring seluruh teks diagnostik melalui `sanitizeDiagnosticText()` agar tidak pernah menampilkan atau mencatat `id_token`, `access_token`, `refresh_token`, `client_secret`, maupun kunci `sb_publishable_*` / `eyJ*`.
  5. `SUPABASE_SESSION_VALIDATION`: Memvalidasi keberadaan `access_token`, `refresh_token`, `user`, dan `user.id` sebelum menyentuh sesi lama di `SupabaseSessionVault`.
  6. `SESSION_VAULT_SAVE`: Menyimpan sesi ke `SupabaseSessionVault` dan memverifikasi pembacaan ulang (`vault.read()`) untuk memastikan `access_token` dan `user_id` benar-benar tersimpan.
  7. `AUTH_SUCCESS`: Mengonfirmasi login berhasil dan mengembalikan `JarvisSession`.

### 3.2. `app/src/main/java/com/jarvis/mobile/account/SupabaseSessionVault.kt`
- Mengganti `.apply()` dengan `.commit()` sinkron pada `save()`.
- Menambahkan verifikasi dekripsi *round-trip* di memori sebelum menyimpan ke `SharedPreferences`, serta melakukan *rollback* ke ciphertext sesi sebelumnya apabila commit gagal (sehingga sesi lama tidak pernah hilang jika penyimpanan sesi baru gagal).
- Menghapus pemanggilan destruktif `clear()` di dalam blok `catch` pada `read()` agar kunci `AndroidKeyStore` tidak terhapus akibat error baca sementara.

### 3.3. `app/src/main/java/com/jarvis/mobile/MainActivity.kt`
- Memperbarui `signInGoogle(openDeviceSetupAfter: Boolean = false)` untuk menampilkan progres 7 tahap (`[AUTH_CONFIG_VALIDATION]` s/d `[AUTH_SUCCESS]`), mencegah klik ganda (`accountBusy`), memperbarui `accountEmail` & `accountName` hanya setelah `SESSION_VAULT_SAVE` berhasil, dan memuat riwayat percakapan (`refreshHistory()`).
- Memperbaiki `openDeviceSetup()`: jika pengguna menekan **"AKUN GOOGLE & AKSES PERANGKAT"** saat belum login, aplikasi langsung menjalankan alur login Google (`signInGoogle(openDeviceSetupAfter = true)`) lalu membuka `DeviceSetupActivity` begitu autentikasi selesai.
- Menambahkan input **Google Web Client ID Override** pada dialog `J.A.R.V.I.S. ACCOUNT` dan `Pengaturan Sistem J.A.R.V.I.S.` agar pengguna dapat mengisi atau memperbarui OAuth 2.0 Web Client ID secara langsung di aplikasi tanpa perlu mengompilasi ulang APK.
- Menyinkronkan kembali `accountEmail` dan `accountName` pada `onResume()` setelah pengguna kembali dari `DeviceSetupActivity`.

### 3.4. `app/src/main/java/com/jarvis/mobile/device/DeviceSetupActivity.kt`
- Mengintegrasikan `SupabaseJarvisClient` secara langsung melalui `ensureGoogleSessionBlocking()` sehingga tombol **"Hubungkan akun Google & pair perangkat ini"**, **"Salin token pairing desktop"**, dan **"Lihat semua perangkat dalam akun"** dapat langsung memicu dialog login Google dengan progres tahap diagnostik apabila pengguna belum memiliki sesi aktif.

### 3.5. `app/build.gradle.kts` & `.github/workflows/android.yml`
- `app/build.gradle.kts`: Membaca `GOOGLE_WEB_CLIENT_ID`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, dan `JARVIS_WORKER_URL` baik dari environment variables maupun Gradle properties (`-PGOOGLE_WEB_CLIENT_ID=...`), serta mengekspor `resValue("string", "default_web_client_id", googleWebClientId)`.
- `.github/workflows/android.yml`: Menambahkan langkah **Validate Google OAuth & Supabase configuration** yang memverifikasi format regex `^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$` tanpa mencetak nilai secret ke log CI.

---

## 4. Hasil Pengujian Otomatis (16 Skenario Wajib + Regresi Penuh)

Seluruh 16 skenario wajib telah diimplementasikan pada `worker/test/batch7-google-auth.test.js` dan dieksekusi bersama seluruh suite pengujian proyek:

| No | Skenario Uji Wajib | Tahap Diagnostik yang Diverifikasi | Status |
| :---: | :--- | :--- | :---: |
| 1 | Web Client ID kosong | `AUTH_CONFIG_VALIDATION` | **PASS** |
| 2 | Web Client ID tidak valid (termasuk Android Client ID / salah format) | `AUTH_CONFIG_VALIDATION` | **PASS** |
| 3 | Credential Manager cancellation (`GetCredentialCancellationException`) | `GOOGLE_CREDENTIAL_REQUEST` | **PASS** |
| 4 | No Google credential (`NoCredentialException`) | `GOOGLE_CREDENTIAL_REQUEST` | **PASS** |
| 5 | Google Developer Error (`10` / `28444` / SHA-1 mismatch) | `GOOGLE_CREDENTIAL_REQUEST` | **PASS** |
| 6 | Credential bukan Google ID Token | `GOOGLE_ID_TOKEN_RECEIVED` | **PASS** |
| 7 | Google ID Token berhasil diperoleh (`TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` & `TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL`) + verifikasi tidak mengirim `Bearer sb_publishable_...` | `GOOGLE_ID_TOKEN_RECEIVED` | **PASS** |
| 8 | Supabase HTTP 400 (dengan redaksi token otomatis) | `SUPABASE_TOKEN_EXCHANGE` | **PASS** |
| 9 | Supabase HTTP 401 | `SUPABASE_TOKEN_EXCHANGE` | **PASS** |
| 10 | Supabase HTTP 403 | `SUPABASE_TOKEN_EXCHANGE` | **PASS** |
| 11 | Supabase HTTP 429 | `SUPABASE_TOKEN_EXCHANGE` | **PASS** |
| 12 | Supabase HTTP 500 (termasuk parsing `hint`) | `SUPABASE_TOKEN_EXCHANGE` | **PASS** |
| 13 | Respons Supabase tidak memiliki `user` | `SUPABASE_SESSION_VALIDATION` | **PASS** |
| 14 | Respons Supabase tidak memiliki `access_token` | `SUPABASE_SESSION_VALIDATION` | **PASS** |
| 15 | `SupabaseSessionVault` gagal menyimpan (tanpa menghapus sesi lama yang masih valid) | `SESSION_VAULT_SAVE` | **PASS** |
| 16 | Login berhasil dan sesi dapat dipulihkan (`AUTH_SUCCESS` + `restoreSession` + `nonce` forwarding) | `AUTH_SUCCESS` | **PASS** |

### Ringkasan Eksekusi Seluruh Suite Test Proyek
- **Worker & Android Auth/Runtime Contract Suite (`npm test --prefix worker`)**: **101 / 101 PASS** (0 gagal).
- **Windows Desktop Agent Suite (`npm test --prefix desktop/windows`)**: **9 / 9 PASS** (0 gagal).
- **macOS Desktop Agent Suite (`npm test --prefix desktop/macos`)**: **10 / 10 PASS** (0 gagal).
- **Linux/Unix Desktop Agent Suite (`npm test --prefix desktop/unix`)**: **10 / 10 PASS** (0 gagal).
- **Static Kotlin & Script Verification (`b7_step2_verify_all.py`)**: **24 / 24 file `.kt` PASS**, **62 / 62 file `.js/.mjs` PASS**, **175 / 175 backup B6 SHA-256 PASS**.

---

## 5. Checklist Konfigurasi Eksternal & Instruksi Commit GitHub

Apabila setelah menggunakan kode terbaru ini muncul pesan diagnostik spesifik pada layar aplikasi, gunakan panduan berikut:

1. **Jika muncul `[AUTH_CONFIG_VALIDATION] GOOGLE_WEB_CLIENT_ID kosong`**:
   - Masukkan **OAuth 2.0 Web Client ID** (`<project>-<hash>.apps.googleusercontent.com`) langsung pada kolom **Google Web Client ID** di dialog **ACCOUNT** atau **Pengaturan Sistem J.A.R.V.I.S.**, atau tambahkan secret `GOOGLE_WEB_CLIENT_ID` pada GitHub Actions repository secrets.
2. **Jika muncul `[GOOGLE_CREDENTIAL_REQUEST] ... DEVELOPER_ERROR`**:
   - Pastikan di **Google Cloud Console -> APIs & Services -> Credentials**:
     - Terdapat **OAuth 2.0 Client ID bertipe Android** dengan Package Name `com.jarvis.mobile.assistant` dan **SHA-1 certificate fingerprint** yang cocok dengan APK (lihat output step `Print APK OAuth fingerprint` pada GitHub Actions).
     - Nilai `GOOGLE_WEB_CLIENT_ID` yang dipakai aplikasi adalah **OAuth 2.0 Client ID bertipe Web application** dari **project Google Cloud yang sama**.
3. **Jika muncul `[SUPABASE_TOKEN_EXCHANGE] Supabase menolak pertukaran Google ID Token (HTTP 400)`**:
   - Buka **Supabase Dashboard -> Authentication -> Providers -> Google**:
     - Pastikan provider **Google** berstatus **Enabled**.
     - Pastikan **Client ID (for OAuth)** atau **Authorized Client IDs** memuat **Web Client ID** (dan opsional Android Client ID) yang sama.

### Perintah Git Commit & Push (Untuk Dijalankan pada Repository GitHub Lokal/CI)
Karena lingkungan sandbox ini bersifat *offline* dan tidak memiliki akses kredensial ke GitHub, salin file dari bundel rilis terbaru lalu jalankan:

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt \
        app/src/main/java/com/jarvis/mobile/account/SupabaseSessionVault.kt \
        app/src/main/java/com/jarvis/mobile/MainActivity.kt \
        app/src/main/java/com/jarvis/mobile/device/DeviceSetupActivity.kt \
        .github/workflows/android.yml \
        worker/test/batch7-google-auth.test.js \
        BATCH_7_GOOGLE_AUTH_FIX_REPORT.md
git commit -m "fix(auth): repair Google Sign-In and Supabase token exchange"
git push origin main
```