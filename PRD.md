# Product Requirements Document (PRD)
**Project Name:** Personal Finance Tracker (Sheets-Synced)
**Platform:** Android (Native)

## 1. Product Overview
Aplikasi pencatatan keuangan pribadi berbasis Android dengan arsitektur offline-first. Fokus utama aplikasi ini adalah *frictionless manual input* (input secepat mungkin) dan sinkronisasi data yang solid ke Google Sheets milik pengguna sebagai *database* pelaporan jangka panjang.

## 2. Core Scopes & Features
### Scope 1: Transactional (Input Engine)
* **Tipe:** Expense, Income, Transfer.
* **Fitur:** In-app numpad custom, *quick preset chips* (akumulatif, e.g., +10k, +50k), auto-select kantong default, dan field catatan opsional (tanpa memicu keyboard OS jika tidak diketuk).
* **Validasi:** Nominal > 0 dan Kategori harus terpilih sebelum tombol Submit aktif.

### Scope 2: History & Audit
* **Fitur:** List view riwayat transaksi harian lokal.
* **Filter:** Berdasarkan Waktu, Tipe (In/Out/Transfer), Kantong, Kategori, dan Status Sinkronisasi (Synced vs Pending).
* **Sorting:** Waktu (Terbaru/Terlama), Nominal (Terbesar/Terkecil).
* **Agregasi:** Dynamic Summary Card untuk menampilkan total nominal sesuai filter yang aktif.

### Scope 3: Customization & Metadata
* **Kantong (Wallets):** Create/Read/Update/Archive. Mendukung *Direct Balance Override* (menimpa saldo manual tanpa log transaksi).
* **Kategori (Categories):** Create/Read/Update/Archive, pembagian Tipe (In/Out), ikon pendukung.
* **Preset Nominal:** Kustomisasi nilai default untuk chip numpad.

### Scope 4: Sync Engine & Integration
* **Pola Sync:** Background offline-first queue menggunakan WorkManager.
* **Endpoint:** Google Apps Script Web App URL (disimpan di SharedPreferences/DataStore).
* **Idempotency:** UUID dari Android untuk mencegah duplikasi baris di Google Sheets.