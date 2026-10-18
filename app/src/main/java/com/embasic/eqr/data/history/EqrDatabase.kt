package com.embasic.eqr.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScanRecordEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EqrDatabase : RoomDatabase() {
    abstract fun scanRecordDao(): ScanRecordDao

    companion object {
        fun create(context: Context): EqrDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EqrDatabase::class.java,
                "embasic-eqr.db",
            ).build()
    }
}
