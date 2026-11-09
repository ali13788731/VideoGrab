package com.videograb.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val fileName: String,
    val kind: String = "HTTP",          // HTTP | HLS
    val status: String = Status.QUEUED.name,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val segDone: Int = 0,
    val segTotal: Int = 0,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val quality: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadItem?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','DOWNLOADING')")
    suspend fun interrupted(): List<DownloadItem>

    @Query("SELECT COUNT(*) FROM downloads WHERE fileName = :name")
    suspend fun nameCount(name: String): Int

    @Insert
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("UPDATE downloads SET downloadedBytes = :bytes, totalBytes = :total, segDone = :segDone, segTotal = :segTotal WHERE id = :id")
    suspend fun progress(id: Long, bytes: Long, total: Long, segDone: Int, segTotal: Int)

    @Query("UPDATE downloads SET fileName = :name WHERE id = :id")
    suspend fun setFileName(id: Long, name: String)
}

@Database(entities = [DownloadItem::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): DownloadDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "videograb.db")
                .build().also { inst = it }
        }
    }
}
