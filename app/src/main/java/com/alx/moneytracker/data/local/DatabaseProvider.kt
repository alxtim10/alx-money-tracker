package com.alx.moneytracker.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Manual-DI singleton holder for the [AppDatabase].
 *
 * [AppDatabase] is abstract (Room generates its implementation), so it must be built via
 * [Room.databaseBuilder]. This object builds a single application-scoped instance using
 * double-checked locking and reuses it for the lifetime of the process, which is the correct
 * lifecycle for a Room database (one instance per app).
 *
 * `fallbackToDestructiveMigration()` is enabled because this is the first schema version and no
 * migrations exist yet; a schema bump simply recreates the database rather than crashing. Replace
 * with real migrations once persisted user data must survive upgrades.
 *
 * A one-time [seedCallback] prepopulates a small set of demo wallets, categories, and quick-preset
 * chips the first time the database file is created, so the input screen is usable on first launch
 * (a default source wallet is selected automatically and categories/presets appear). Seeding runs
 * only in `onCreate`, so it never overwrites data a user has already entered.
 */
object DatabaseProvider {

    @Volatile
    private var instance: AppDatabase? = null

    /** Returns the process-wide [AppDatabase], building it on first use. */
    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

    private fun build(context: Context): AppDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .addCallback(seedCallback)
            .build()

    /**
     * Inserts demo data exactly once, when the database file is first created. Uses raw SQL because
     * the DAOs intentionally expose only observation + the atomic transaction write, not direct
     * wallet/category inserts. Balances are in the smallest currency unit (rupiah).
     */
    private val seedCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            // Wallets: one default source wallet plus a second for TRANSFER destinations.
            db.execSQL(
                "INSERT INTO wallets (name, balance, is_default, is_archived) VALUES " +
                    "('Cash', 500000, 1, 0), " +
                    "('Bank', 2500000, 0, 0)"
            )

            // Categories per type (icon is a free-form identifier for now).
            db.execSQL(
                "INSERT INTO categories (name, type, icon, is_archived) VALUES " +
                    "('Makan', 'EXPENSE', 'food', 0), " +
                    "('Transport', 'EXPENSE', 'bus', 0), " +
                    "('Belanja', 'EXPENSE', 'cart', 0), " +
                    "('Tagihan', 'EXPENSE', 'bill', 0), " +
                    "('Gaji', 'INCOME', 'salary', 0), " +
                    "('Bonus', 'INCOME', 'gift', 0), " +
                    "('Pindah Dana', 'TRANSFER', 'swap', 0)"
            )

            // Quick-preset chips (additive amounts), ordered by amount ascending in the DAO.
            db.execSQL(
                "INSERT INTO quick_presets (amount, label) VALUES " +
                    "(10000, '+10rb'), " +
                    "(20000, '+20rb'), " +
                    "(50000, '+50rb'), " +
                    "(100000, '+100rb')"
            )
        }
    }
}
