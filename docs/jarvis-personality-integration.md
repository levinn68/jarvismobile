# JARVIS Personality Integration

Paket ini menambahkan gabungan pendekatan personality dari tiga referensi:
- InterGenJLU/jarvis: response pools dan konteks percakapan.
- KKshitiz/J.A.R.V.I.S: variasi frasa untuk situasi/perintah.
- isair/jarvis: aturan British-butler, dry humour, dan conversational behavior.

Implementasi ini ditulis ulang sebagai modul orisinal untuk backend JARVIS Mobile. Ini bukan salinan utuh kode dari repository referensi.

## Isi paket
- `worker/src/personality.js` — Personality Engine, response pools, deteksi kategori, pemilih variasi yang menghindari pengulangan terbaru, dan system prompt.
- `tools/apply-personality.mjs` — patcher untuk mengintegrasikan Personality Engine ke `worker/src/index.js` yang sudah ada.
- `README.md` — panduan ini.

## Cara memasang
1. Buat backup proyek JARVIS Mobile.
2. Ekstrak isi ZIP ke root repository `levinn68/jarvismobile`, gabungkan folder `worker` dan `tools` dengan folder yang sudah ada.
3. Pastikan file `worker/src/index.js` masih merupakan backend JARVIS terbaru.
4. Dari root repository, jalankan:

   `node tools/apply-personality.mjs`

5. Periksa perubahan pada `worker/src/index.js`. Patcher membuat backup bernama `worker/src/index.js.before-personality.bak`.
6. Deploy Worker seperti biasa setelah pengujian.

## Yang dipertahankan
Patcher tidak mengganti provider, endpoint, routing, pencarian, TTS, atau format respons JSON. Ia hanya:
- menambahkan import Personality Engine;
- membangun personality prompt per pesan;
- menyisipkan personality prompt ke system prompt semua agent, reviewer, fallback, dan synthesis.

Endpoint yang sudah ada tetap dipertahankan oleh backend asli:
- `POST /v1/chat`
- `POST /v1/tts`
- `POST /v1/search/products`
- `POST /v1/search/images`
- `GET /health`

## API keys dan secrets
ZIP ini tidak menyertakan API key, `.dev.vars`, token, atau Cloudflare Secrets. API keys tetap berada di Cloudflare Worker Secrets dan tidak disentuh oleh patcher.

## Catatan
Paket ini merupakan integrasi modul + patcher, bukan salinan penuh backend. Ini sengaja dilakukan agar backend aktif tidak tertimpa oleh versi backend yang direkonstruksi atau tidak lengkap. Patcher akan berhenti tanpa mengubah file jika titik integrasi `systemBase` tidak ditemukan.