package com.aishell.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aishell.data.local.dao.ConversationDao
import com.aishell.data.local.dao.MessageDao
import com.aishell.data.local.dao.ToolCallDao
import com.aishell.data.local.entity.ConversationEntity
import com.aishell.data.local.entity.MessageEntity
import com.aishell.data.local.entity.ToolCallEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ToolCallEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AiShellDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao

    companion object {
        fun create(context: Context): AiShellDatabase {
            return Room.databaseBuilder(
                context,
                AiShellDatabase::class.java,
                "aishell.db"
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA synchronous=NORMAL")
                        db.execSQL("PRAGMA cache_size=-64000")
                    }
                })
                .build()
        }
    }
}