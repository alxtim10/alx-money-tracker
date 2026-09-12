# Development Tasks

## Phase 1: Foundation & DB Setup
- [ ] Inisialisasi Android Project (Empty Compose Activity).
- [ ] Setup dependencies (Room, Ktor/Retrofit, WorkManager, ViewModel, Serialization).
- [ ] Buat Room Database (Entity: `Transaction`, `Wallet`, `Category`, `Preset`).
- [ ] Buat Repository layer & Flow observables.
- [ ] Data Seeding: Insert default categories & quick presets saat app pertama kali dibuka.

## Phase 2: Transaction Scope (Input Form)
- [ ] Buat `CustomNumpad` Composable.
- [ ] Buat `QuickPresetChips` Composable.
- [ ] Buat UI Selektor Kategori & Kantong (BottomSheet / Grid).
- [ ] Implementasi form validasi dan logika akumulasi numpad.
- [ ] Integrasikan `Save` button ke Repository.

## Phase 3: History Scope (Log & Audit)
- [ ] Buat UI Transaction List dengan adapter/LazyColumn.
- [ ] Implementasi Filter Header (Horizontal Chips).
- [ ] Implementasi Sort logic (Amount, Time) di Room Query / ViewModel.
- [ ] Buat Dynamic Summary Card berdasar filter aktif.

## Phase 4: Sync & Integration
- [ ] Deploy script `doPost` di Google Apps Script (siapkan 1 sheet kosong).
- [ ] Buat Settings Screen untuk menyimpan URL Apps Script via DataStore.
- [ ] Implementasi `WorkManager` untuk batch pengiriman transaksi offline (`is_synced = 0`).
- [ ] Update status UI indikator sync (Centang/Jam Pasir) pada Transaction List.

## Phase 5: Customization & Polish
- [ ] Buat layar Wallet Management (Add/Edit/Direct Override Balance).
- [ ] Buat layar Category Management.
- [ ] Finalisasi icon, color theme, dan haptic feedback pada numpad.
