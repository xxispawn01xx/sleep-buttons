package app.sleepbuttons.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.domain.AlarmStatus
import app.sleepbuttons.domain.SleepRecord
import app.sleepbuttons.domain.SleepType
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Current state of one alarm slot (one row per slot). */
@Entity(tableName = "alarm_state")
data class AlarmStateEntity(
    @PrimaryKey val slot: String,
    val status: String,
    val triggerAtEpochMs: Long,
    /** Nap only: SystemClock.elapsedRealtime() target, valid while [bootCount] matches. */
    val triggerElapsedMs: Long? = null,
    val bootCount: Int? = null,
    /** Melatonin only: saved local wall-clock target, ISO LocalDateTime. */
    val localDateTime: String? = null,
    val napAttemptId: Long? = null,
    /** Melatonin only: dose snapshot at scheduling time. */
    val doseMicrograms: Int? = null,
    val note: String? = null,
    val updatedAtEpochMs: Long,
) {
    val alarmSlot: AlarmSlot get() = AlarmSlot.fromId(slot)!!
    val alarmStatus: AlarmStatus get() = AlarmStatus.valueOf(status)
}

@Entity(tableName = "nap_attempt")
data class NapAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val zoneId: String,
    val firstOffsetMin: Int,
    val secondOffsetMin: Int,
    val endedAtEpochMs: Long? = null,
    val endReason: String? = null,
    /** True once the user has logged (or dismissed logging for) this attempt. */
    val logHandled: Boolean = false,
)

@Entity(tableName = "sleep_entry")
data class SleepEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val inBedEpochMs: Long? = null,
    val onsetEpochMs: Long? = null,
    val wakeEpochMs: Long? = null,
    val awakeMinutes: Int? = null,
    val notes: String = "",
    val zoneId: String,
) {
    fun toRecord(): SleepRecord {
        val z = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
        fun t(ms: Long?) = ms?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), z) }
        return SleepRecord(SleepType.valueOf(type), t(inBedEpochMs), t(onsetEpochMs), t(wakeEpochMs), awakeMinutes)
    }
}

@Entity(tableName = "dose_log")
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val takenAtEpochMs: Long,
    val doseMicrograms: Int,
    /** "reminder" (Taken button) or "manual". */
    val source: String,
    val zoneId: String,
    val notes: String = "",
)

@Dao
interface AlarmStateDao {
    @Query("SELECT * FROM alarm_state")
    fun observeAll(): Flow<List<AlarmStateEntity>>

    @Query("SELECT * FROM alarm_state")
    suspend fun all(): List<AlarmStateEntity>

    @Query("SELECT * FROM alarm_state WHERE slot = :slot")
    suspend fun get(slot: String): AlarmStateEntity?

    @Upsert
    suspend fun upsert(state: AlarmStateEntity)
}

@Dao
interface NapAttemptDao {
    @Insert
    suspend fun insert(a: NapAttemptEntity): Long

    @Query("SELECT * FROM nap_attempt WHERE id = :id")
    suspend fun get(id: Long): NapAttemptEntity?

    @Query("UPDATE nap_attempt SET endedAtEpochMs = :endedAt, endReason = :reason WHERE id = :id AND endedAtEpochMs IS NULL")
    suspend fun end(id: Long, endedAt: Long, reason: String)

    @Query("UPDATE nap_attempt SET logHandled = 1 WHERE id = :id")
    suspend fun markLogHandled(id: Long)

    @Query("SELECT * FROM nap_attempt WHERE endedAtEpochMs IS NOT NULL AND logHandled = 0 ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeUnloggedEnded(): Flow<NapAttemptEntity?>
}

@Dao
interface SleepEntryDao {
    @Query("SELECT * FROM sleep_entry ORDER BY COALESCE(wakeEpochMs, onsetEpochMs, inBedEpochMs) DESC")
    fun observeAll(): Flow<List<SleepEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SleepEntryEntity): Long

    @Delete
    suspend fun delete(e: SleepEntryEntity)
}

@Dao
interface DoseLogDao {
    @Query("SELECT * FROM dose_log ORDER BY takenAtEpochMs DESC")
    fun observeAll(): Flow<List<DoseLogEntity>>

    @Query("SELECT * FROM dose_log WHERE takenAtEpochMs >= :fromMs AND takenAtEpochMs < :toMs ORDER BY takenAtEpochMs")
    suspend fun between(fromMs: Long, toMs: Long): List<DoseLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: DoseLogEntity): Long

    @Delete
    suspend fun delete(e: DoseLogEntity)
}

@Database(
    entities = [AlarmStateEntity::class, NapAttemptEntity::class, SleepEntryEntity::class, DoseLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmStateDao
    abstract fun naps(): NapAttemptDao
    abstract fun sleep(): SleepEntryDao
    abstract fun doses(): DoseLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sleep_buttons.db").build()
    }
}
