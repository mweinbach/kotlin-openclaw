package ai.openclaw.core.storage

import ai.openclaw.core.storage.dao.ChannelStateDao
import ai.openclaw.core.storage.dao.CronJobDao
import ai.openclaw.core.storage.dao.MemoryDao
import ai.openclaw.core.storage.dao.MessageDao
import ai.openclaw.core.storage.dao.PluginStateDao
import ai.openclaw.core.storage.dao.SessionDao
import ai.openclaw.core.storage.entity.ChannelStateEntity
import ai.openclaw.core.storage.entity.CronJobEntity
import ai.openclaw.core.storage.entity.MemoryEntryEntity
import ai.openclaw.core.storage.entity.MessageEntity
import ai.openclaw.core.storage.entity.PluginStateEntity
import ai.openclaw.core.storage.entity.SessionEntity
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntryEntity::class,
        CronJobEntity::class,
        ChannelStateEntity::class,
        PluginStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenClawDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun cronJobDao(): CronJobDao
    abstract fun channelStateDao(): ChannelStateDao
    abstract fun pluginStateDao(): PluginStateDao

    companion object {
        private const val DATABASE_NAME = "openclaw.db"

        @Volatile
        private var instance: OpenClawDatabase? = null

        fun getInstance(context: Context): OpenClawDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): OpenClawDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                OpenClawDatabase::class.java,
                DATABASE_NAME,
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
