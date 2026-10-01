package jp.personal.fukuiepg.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channel")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val band: String,      // GR / BS / CS
    val number: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "program",
    indices = [Index("channelId"), Index("startAt"), Index("endAt")],
)
data class ProgramEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val startAt: Long,     // epoch millis
    val endAt: Long,
    val title: String,
    val description: String,
    val genre: String,
)

/** お気に入り。type=PROGRAM は番組、type=KEYWORD はキーワード。 */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val programId: String? = null,
    val channelId: String? = null,
    val title: String? = null,
    val startAt: Long? = null,
    val keyword: String? = null,
    val minutesBefore: Int = 5,
) {
    companion object {
        const val PROGRAM = "PROGRAM"
        const val KEYWORD = "KEYWORD"
    }
}

/** 番組＋チャンネル名（画面表示用） */
data class ProgramWithChannel(
    val id: String,
    val channelId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String,
    val genre: String,
    val channelName: String,
    val channelNumber: String,
    val band: String,
)

@Dao
interface EpgDao {
    @Query("SELECT * FROM channel ORDER BY sortOrder")
    fun channels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM program WHERE endAt > :from AND startAt < :to ORDER BY channelId, startAt")
    fun programsBetween(from: Long, to: Long): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM program WHERE endAt > :from AND startAt < :to ORDER BY channelId, startAt")
    suspend fun programsBetweenOnce(from: Long, to: Long): List<ProgramEntity>

    @Query(
        """SELECT p.*, c.name AS channelName, c.number AS channelNumber, c.band AS band
           FROM program p JOIN channel c ON p.channelId = c.id
           WHERE p.endAt > :now AND (p.title LIKE '%' || :q || '%' OR p.description LIKE '%' || :q || '%')
           ORDER BY p.startAt LIMIT 300"""
    )
    fun search(q: String, now: Long): Flow<List<ProgramWithChannel>>

    @Query(
        """SELECT p.*, c.name AS channelName, c.number AS channelNumber, c.band AS band
           FROM program p JOIN channel c ON p.channelId = c.id
           WHERE p.endAt > :now AND p.title LIKE '%' || :q || '%'
           ORDER BY p.startAt"""
    )
    suspend fun searchTitleOnce(q: String, now: Long): List<ProgramWithChannel>

    @Query(
        """SELECT p.*, c.name AS channelName, c.number AS channelNumber, c.band AS band
           FROM program p JOIN channel c ON p.channelId = c.id WHERE p.id = :id"""
    )
    suspend fun programById(id: String): ProgramWithChannel?

    @Query(
        """SELECT p.*, c.name AS channelName, c.number AS channelNumber, c.band AS band
           FROM program p JOIN channel c ON p.channelId = c.id
           WHERE p.channelId = :channelId AND p.title = :title
           ORDER BY ABS(p.startAt - :near) LIMIT 1"""
    )
    suspend fun findSameProgram(channelId: String, title: String, near: Long): ProgramWithChannel?

    @Query("SELECT COUNT(*) FROM program")
    suspend fun programCount(): Int

    @Upsert
    suspend fun upsertChannels(list: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE id NOT IN (:keep)")
    suspend fun deleteChannelsExcept(keep: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(list: List<ProgramEntity>)

    @Query("DELETE FROM program WHERE startAt >= :from AND startAt < :to")
    suspend fun deleteProgramsStarting(from: Long, to: Long)

    @Query("DELETE FROM program WHERE endAt < :before")
    suspend fun deleteOld(before: Long)

    // --- お気に入り ---
    @Query("SELECT * FROM favorite ORDER BY type, startAt, keyword")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite")
    suspend fun favoritesOnce(): List<FavoriteEntity>

    @Query("SELECT * FROM favorite WHERE type = 'PROGRAM' AND programId = :programId LIMIT 1")
    suspend fun favoriteByProgram(programId: String): FavoriteEntity?

    @Query("SELECT programId FROM favorite WHERE type = 'PROGRAM' AND programId IS NOT NULL")
    fun favoriteProgramIds(): Flow<List<String>>

    @Insert
    suspend fun insertFavorite(f: FavoriteEntity): Long

    @Upsert
    suspend fun upsertFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE id = :id")
    suspend fun deleteFavorite(id: Long)

    @Query("DELETE FROM favorite WHERE type = 'PROGRAM' AND startAt < :before")
    suspend fun deleteExpiredFavorites(before: Long)
}

@Database(
    entities = [ChannelEntity::class, ProgramEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class EpgDatabase : RoomDatabase() {
    abstract fun dao(): EpgDao

    companion object {
        @Volatile private var instance: EpgDatabase? = null
        fun get(context: Context): EpgDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EpgDatabase::class.java, "epg.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
