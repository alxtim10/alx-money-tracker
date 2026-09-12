# System Architecture

## 1. Technology Stack
* **UI Layer:** Jetpack Compose, Material Design 3.
* **Local Data & Logic:** Room Database (SQLite), Kotlin Coroutines, ViewModel.
* **Background Sync:** Android WorkManager.
* **Network:** Ktor Client / Retrofit, Kotlinx Serialization.
* **Backend / Cloud Storage:** Google Apps Script (Webhook) & Google Sheets.

## 2. System Context
[Android App] <---(HTTP POST)---> [Google Apps Script] ---> [Google Sheets]
      |                                  |
   (Room DB)                      (Parse JSON & AppendRow)
   - Cache lokal
   - State saldo riil

## 3. Offline-First Sync Flow
1. User input data -> Simpan ke Room DB (`transactions` tabel) dengan `is_synced = 0`.
2. Update saldo di tabel `wallets` dalam satu `@Transaction`.
3. UI langsung update tanpa loading jaringan.
4. App mendaftarkan tugas ke `WorkManager` (Constraint: Network Connected).
5. WorkManager hit API. Jika `200 OK`, eksekusi DB `UPDATE transactions SET is_synced = 1`.

## 4. Database Schema
Terdiri dari 4 tabel utama:
1. `transactions` (id, type, amount, source_wallet, dest_wallet, category_id, note, is_synced)
2. `wallets` (id, name, balance, is_default)
3. `categories` (id, name, type, icon)
4. `quick_presets` (id, amount, label)
