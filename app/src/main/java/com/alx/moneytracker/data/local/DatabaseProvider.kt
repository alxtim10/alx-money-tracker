package com.alx.moneytracker.data.local

import android.content.Context
import androidx.room.Room

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
            .build()
}
