# Agent Directives

## UI/UX Agent (Jetpack Compose)
* **Goal:** Membangun antarmuka pengguna tanpa gesekan (frictionless).
* **Rule 1:** Jangan gunakan system keyboard untuk input angka. Gunakan Custom Numpad Composable.
* **Rule 2:** Fokus pada penggunaan satu tangan (ergonomis). Letakkan area interaksi utama (numpad, tombol submit) di area bawah layar.

## Data/DB Agent (Room & Repository)
* **Goal:** Menjamin integritas data lokal.
* **Rule 1:** Selalu gunakan `@Transaction` saat menyimpan transaksi baru (insert log + update saldo kantong).
* **Rule 2:** Gunakan Flow/StateFlow untuk observasi perubahan tabel secara real-time ke UI.
* **Rule 3:** Modifikasi saldo kantong langsung (*override*) tidak boleh menghasilkan entri di tabel transaksi.

## Sync/Network Agent (WorkManager & HTTP)
* **Goal:** Mengirim data ke backend dengan aman.
* **Rule 1:** Tangani limitasi eksekusi Apps Script (HTTP 302 Redirect). Pastikan HTTP client (OkHttp/Ktor) memiliki konfigurasi `followRedirects = true`.
* **Rule 2:** Jangan memblokir Main Thread UI untuk API Call. Semua via WorkManager atau Dispatchers.IO.
