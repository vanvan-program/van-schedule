// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "schedules")
data class ScheduleRecord(@PrimaryKey val id: String, val payload: String)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY rowid") fun observe(): Flow<List<ScheduleRecord>>

    @Query("SELECT * FROM schedules ORDER BY rowid") suspend fun all(): List<ScheduleRecord>

    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun find(id: String): ScheduleRecord?

    @Upsert suspend fun put(record: ScheduleRecord)

    @Query("DELETE FROM schedules WHERE id=:id") suspend fun delete(id: String)

    @Query("DELETE FROM schedules") suspend fun clear()
}

@Database(entities = [ScheduleRecord::class], version = 1, exportSchema = true)
abstract class VanDatabase : RoomDatabase() {
    abstract fun schedules(): ScheduleDao
}

private val Context.settings by preferencesDataStore("van_settings")

private const val TAG = "Store"

class Store(private val context: Context) {
    private val db =
        Room.databaseBuilder(context.applicationContext, VanDatabase::class.java, "van.db").build()
    private val preferencesKey = stringPreferencesKey("preferences_v1")
    private val lock = Mutex()
    private val snapshot = AtomicFile(File(context.filesDir, "before-restore.van.json"))
    val schedules: Flow<List<Schedule>> =
        db.schedules().observe().map { rows -> rows.mapNotNull(::decodeRow) }

    /**
     * 逐行解码：某一行 payload 损坏（升级残留、手动改库）时只跳过那一行，
     * 不让整个列表抛异常、把用户所有课表都变成空白。
     * 严格版本见 [all]：备份与导出应该失败即失败，不能静默少数据。
     */
    private fun decodeRow(row: ScheduleRecord): Schedule? =
        runCatching { Codec.decode(row.payload) }
            .onFailure { Log.w(TAG, "课表记录 ${row.id} 解码失败，已跳过该条", it) }
            .getOrNull()

    val preferences: Flow<Preferences> =
        context.settings.data.map { p ->
            p[preferencesKey]?.let { Codec.json.decodeFromString(Preferences.serializer(), it) }
                ?: Preferences()
        }

    suspend fun all(): List<Schedule> = db.schedules().all().map { Codec.decode(it.payload) }

    /** 只读取指定课表。课表不存在或该条记录无法解码时返回 null。 */
    suspend fun find(id: String): Schedule? = db.schedules().find(id)?.let(::decodeRow)

    suspend fun prefs(): Preferences = preferences.first()

    suspend fun save(s: Schedule) {
        Validation.requireValid(s)
        db.schedules().put(ScheduleRecord(s.id, Codec.encode(s)))
    }

    suspend fun updatePreferences(transform: (Preferences) -> Preferences) {
        context.settings.edit { p ->
            val before =
                p[preferencesKey]?.let { Codec.json.decodeFromString(Preferences.serializer(), it) }
                    ?: Preferences()
            p[preferencesKey] =
                Codec.json.encodeToString(Preferences.serializer(), transform(before))
        }
    }

    suspend fun delete(id: String) {
        lock.withLock {
            db.schedules().delete(id)
            updatePreferences {
                if (it.activeScheduleId == id) it.copy(activeScheduleId = null) else it
            }
        }
    }

    suspend fun backup(): String = Codec.backup(Backup(schedules = all(), preferences = prefs()))

    suspend fun readBackupSnapshot(): Backup = lock.withLock {
        snapshot.openRead().bufferedReader().use { Codec.restore(it.readText()) }
    }

    private fun writeBackupSnapshot(content: String) {
        val output = snapshot.startWrite()
        try {
            output.write(content.toByteArray())
            snapshot.finishWrite(output)
        } catch (error: Exception) {
            snapshot.failWrite(output)
            throw error
        }
        check(snapshot.openRead().bufferedReader().use { it.readText() } == content) {
            "覆盖前快照保存失败，课表未更新"
        }
    }

    suspend fun updateFromImport(original: Schedule, updated: Schedule, globalDays: List<DayOverride>) {
        require(original.id == updated.id)
        Validation.requireValid(updated)
        lock.withLock {
            db.withTransaction {
                val schedules = all()
                val preferences = prefs()
                require(schedules.find { it.id == original.id } == original && preferences.globalDayOverrides == globalDays) {
                    "课表或全局调休已变更，请重新核对导入差异"
                }
                writeBackupSnapshot(Codec.backup(Backup(schedules = schedules, preferences = preferences)))
                save(updated)
            }
        }
    }

    suspend fun restore(b: Backup) {
        require(b.scope == "full")
        b.schedules.forEach(Validation::requireValid)
        lock.withLock {
            writeBackupSnapshot(backup())
            val oldPrefs = prefs()
            db.withTransaction {
                db.schedules().clear()
                b.schedules.forEach { db.schedules().put(ScheduleRecord(it.id, Codec.encode(it))) }
                try {
                    updatePreferences {
                        (b.preferences ?: oldPrefs).copy(
                            activeScheduleId =
                                b.preferences?.activeScheduleId?.takeIf { id ->
                                    b.schedules.any { it.id == id }
                                }
                        )
                    }
                } catch (e: Exception) {
                    updatePreferences { oldPrefs }
                    throw e
                }
            }
        }
    }

    suspend fun active(): Schedule? {
        val id = prefs().activeScheduleId ?: return null
        return find(id)
    }
}

object Demo {
    fun schedule(): Schedule {
        val monday =
            LocalDate.now(ZoneId.of(DEFAULT_ZONE))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(5)
        val names = listOf("大数据技术", "操作系统", "数据库原理", "Java程序设计", "汇编语言程序设计")
        val courses = names.mapIndexed { i, n ->
            Course(name = n, color = i, note = "示例课程，时间与地点可编辑")
        }
        val slots =
            listOf(
                1 to 1,
                1 to 5,
                2 to 3,
                3 to 1,
                3 to 7,
                4 to 3,
                4 to 5,
                5 to 1,
                5 to 7,
                6 to 3,
                7 to 1,
                7 to 3,
            )
        val sessions = slots.mapIndexed { i, p ->
            CourseSession(
                courseId = courses[i % 5].id,
                day = p.first,
                startSection = p.second,
                endSection = p.second + 1,
                teacher = listOf("李老师", "张老师", "王老师")[i % 3],
                place = "示例楼 A${listOf(414,214,302,101)[i%4]}",
            )
        }
        return Schedule(
            name = "示例 · 秋季课表",
            week1Monday = monday.toString(),
            courses = courses,
            sessions = sessions,
            source = "示例数据",
        )
    }
}
