package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [BookmarkEntity::class, HistoryEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OnionShieldDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: OnionShieldDatabase? = null

        fun getInstance(context: Context): OnionShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OnionShieldDatabase::class.java,
                    "onionshield_local.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class BrowserLibraryRepository(private val database: OnionShieldDatabase) {
    private val bookmarkDao = database.bookmarkDao()
    private val historyDao = database.historyDao()
    private val downloadDao = database.downloadDao()

    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.observeAll()
    val history: Flow<List<HistoryEntity>> = historyDao.observeAll()
    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll()

    fun observeIsBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun toggleBookmark(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        val existing = bookmarkDao
        // Use simple delete-or-insert via query check; insert replaces on conflict if same pk,
        // so we delete by url if already present via caller or check.
        bookmarkDao.deleteByUrl(url)
        bookmarkDao.insert(
            BookmarkEntity(
                title = title.ifBlank { url },
                url = url,
                isOnion = url.contains(".onion", ignoreCase = true),
            )
        )
    }

    suspend fun addBookmark(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        bookmarkDao.deleteByUrl(url)
        bookmarkDao.insert(
            BookmarkEntity(
                title = title.ifBlank { url },
                url = url,
                isOnion = url.contains(".onion", ignoreCase = true),
            )
        )
    }

    suspend fun removeBookmarkByUrl(url: String) = bookmarkDao.deleteByUrl(url)

    suspend fun removeBookmarkById(id: Long) = bookmarkDao.deleteById(id)

    suspend fun recordHistory(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        if (url.contains(".onion", ignoreCase = true)) return // Never persist .onion visits
        historyDao.insert(
            HistoryEntity(
                title = title.ifBlank { url },
                url = url,
                isOnion = false,
            )
        )
    }

    suspend fun deleteHistoryItem(id: Long) = historyDao.deleteById(id)

    suspend fun clearAllHistory() = historyDao.clearAll()

    suspend fun recordDownload(download: DownloadEntity): Long = downloadDao.insert(download)

    suspend fun updateDownloadStatus(id: Long, status: String) = downloadDao.updateStatus(id, status)

    suspend fun deleteDownload(id: Long) = downloadDao.deleteById(id)

    suspend fun clearDownloads() = downloadDao.clearAll()
}
