package com.agent.voiceassistant.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class,
        TaskReportActionEntity::class,
        TaskArtifactEntity::class,
        TaskEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var instance: TaskDatabase? = null

        fun get(context: Context): TaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TaskDatabase::class.java,
                "agent-tasks.db",
            ).build().also { instance = it }
        }
    }
}
