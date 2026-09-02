package com.elderptod.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.OffsetDateTime

data class ReminderDefinition(
    val id: String,
    val title: String,
    val message: String,
    val scheduledAt: String,
    val repeatRule: String,
    val enabled: Boolean,
    val updatedAt: String,
    val audioType: String = "tts",
    val audioUrl: String? = null,
    val audioContentType: String? = null,
    val audioFilename: String? = null,
)

data class ReminderAlarmItem(
    val reminderId: String,
    val scheduledAt: OffsetDateTime,
)

class ReminderLocalStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE reminder_definitions (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                title TEXT NOT NULL,
                message TEXT NOT NULL,
                scheduled_at TEXT NOT NULL,
                repeat_rule TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                audio_type TEXT NOT NULL DEFAULT 'tts',
                audio_url TEXT,
                audio_content_type TEXT,
                audio_filename TEXT,
                updated_at TEXT NOT NULL,
                sync_version INTEGER NOT NULL,
                server_time TEXT,
                locally_updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE reminder_execution_state (
                reminder_id TEXT PRIMARY KEY,
                state TEXT NOT NULL,
                scheduled_locally_at TEXT,
                triggered_at TEXT,
                played_at TEXT,
                acknowledged_at TEXT,
                failed_at TEXT,
                expired_at TEXT,
                error TEXT,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE reminder_sync_state (
                device_id TEXT PRIMARY KEY,
                sync_version INTEGER NOT NULL,
                server_time TEXT,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_reminder_definitions_next
            ON reminder_definitions(device_id, enabled, scheduled_at)
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            onCreate(db)
        }
        if (oldVersion < 2) {
            addColumnIfMissing(
                db,
                "reminder_definitions",
                "audio_type",
                "TEXT NOT NULL DEFAULT 'tts'",
            )
            addColumnIfMissing(db, "reminder_definitions", "audio_url", "TEXT")
            addColumnIfMissing(db, "reminder_definitions", "audio_content_type", "TEXT")
            addColumnIfMissing(db, "reminder_definitions", "audio_filename", "TEXT")
        }
    }

    fun applySync(
        deviceId: String,
        syncVersion: Long,
        serverTime: String?,
        reminders: List<ReminderDefinition>,
    ): ReminderState? {
        writableDatabase.use { db ->
            val currentVersion = currentSyncVersion(db, deviceId)
            if (syncVersion < currentVersion) {
                return nextReminder()
            }
            db.beginTransaction()
            try {
                val ids = reminders.map { it.id }.filter { it.isNotBlank() }.toSet()
                if (ids.isEmpty()) {
                    db.delete("reminder_definitions", "device_id = ?", arrayOf(deviceId))
                } else {
                    val placeholders = ids.joinToString(",") { "?" }
                    db.delete(
                        "reminder_definitions",
                        "device_id = ? AND id NOT IN ($placeholders)",
                        arrayOf(deviceId, *ids.toTypedArray()),
                    )
                }
                reminders.forEach { reminder ->
                    val isNewOccurrence = isNewOccurrence(db, reminder)
                    upsertDefinition(db, deviceId, syncVersion, serverTime, reminder)
                    if (isNewOccurrence) {
                        resetExecutionState(db, reminder.id)
                    }
                    insertExecutionStateIfMissing(db, reminder.id)
                }
                upsertSyncState(db, deviceId, syncVersion, serverTime)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return nextReminder()
    }

    fun nextReminder(): ReminderState? {
        readableDatabase.use { db ->
            val next = nextCandidate(db, after = OffsetDateTime.now()) ?: return null
            return next.toReminderState()
        }
    }

    fun nextAlarmReminder(): ReminderAlarmItem? {
        readableDatabase.use { db ->
            val next = nextCandidate(db, after = OffsetDateTime.now()) ?: return null
            return ReminderAlarmItem(
                reminderId = next.reminderId,
                scheduledAt = next.parsed,
            )
        }
    }

    fun dueAlarmReminder(): ReminderState? {
        readableDatabase.use { db ->
            val now = OffsetDateTime.now()
            val due = enabledCandidates(db)
                .filter { candidate -> candidate.parsed <= now && !candidate.isTerminal }
                .maxByOrNull { candidate -> candidate.parsed }
                ?: return null
            return due.toReminderState()
        }
    }

    fun reminderById(reminderId: String?): ReminderState? {
        if (reminderId.isNullOrBlank()) return null
        readableDatabase.use { db ->
            val cursor = db.rawQuery(
                """
                SELECT d.id, d.title, d.message, d.scheduled_at,
                       d.audio_type, d.audio_url, d.audio_content_type, d.audio_filename,
                       s.state, s.updated_at
                FROM reminder_definitions d
                LEFT JOIN reminder_execution_state s ON s.reminder_id = d.id
                WHERE d.id = ? AND d.enabled = 1
                LIMIT 1
                """.trimIndent(),
                arrayOf(reminderId),
            )
            cursor.use {
                if (!it.moveToFirst()) return null
                val state = it.getString(8)
                val stateUpdatedAt = it.getString(9)
                if (isTerminalForOccurrence(state, it.getString(3), stateUpdatedAt)) {
                    return null
                }
                return ReminderState(
                    title = it.getString(1),
                    message = it.getString(2),
                    timeText = formatReminderTime(it.getString(3)),
                    reminderId = it.getString(0),
                    audioType = it.getString(4),
                    audioUrl = it.getNullableString(5),
                    audioContentType = it.getNullableString(6),
                    audioFilename = it.getNullableString(7),
                )
            }
        }
    }

    fun markExecutionState(reminderId: String?, state: String, error: String? = null) {
        if (reminderId.isNullOrBlank()) return
        val now = OffsetDateTime.now().toString()
        writableDatabase.use { db ->
            val values = ContentValues().apply {
                put("reminder_id", reminderId)
                put("state", state)
                when (state) {
                    "scheduled_locally" -> put("scheduled_locally_at", now)
                    "triggered" -> put("triggered_at", now)
                    "played" -> put("played_at", now)
                    "acknowledged" -> put("acknowledged_at", now)
                    "failed" -> {
                        put("failed_at", now)
                        put("error", error)
                    }
                    "expired" -> put("expired_at", now)
                }
                put("updated_at", now)
            }
            db.insertWithOnConflict(
                "reminder_execution_state",
                null,
                ContentValues().apply {
                    put("reminder_id", reminderId)
                    put("state", "synced_locally")
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.update("reminder_execution_state", values, "reminder_id = ?", arrayOf(reminderId))
        }
    }

    private fun upsertDefinition(
        db: SQLiteDatabase,
        deviceId: String,
        syncVersion: Long,
        serverTime: String?,
        reminder: ReminderDefinition,
    ) {
        val values = ContentValues().apply {
            put("id", reminder.id)
            put("device_id", deviceId)
            put("title", reminder.title)
            put("message", reminder.message)
            put("scheduled_at", reminder.scheduledAt)
            put("repeat_rule", reminder.repeatRule)
            put("enabled", if (reminder.enabled) 1 else 0)
            put("audio_type", reminder.audioType)
            put("audio_url", reminder.audioUrl)
            put("audio_content_type", reminder.audioContentType)
            put("audio_filename", reminder.audioFilename)
            put("updated_at", reminder.updatedAt)
            put("sync_version", syncVersion)
            put("server_time", serverTime)
            put("locally_updated_at", OffsetDateTime.now().toString())
        }
        db.insertWithOnConflict(
            "reminder_definitions",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun isNewOccurrence(db: SQLiteDatabase, reminder: ReminderDefinition): Boolean {
        val cursor = db.query(
            "reminder_definitions",
            arrayOf("scheduled_at", "enabled"),
            "id = ?",
            arrayOf(reminder.id),
            null,
            null,
            null,
            "1",
        )
        cursor.use {
            if (!it.moveToFirst()) return true
            val currentScheduledAt = it.getString(0)
            val currentEnabled = it.getInt(1) == 1
            return currentScheduledAt != reminder.scheduledAt || currentEnabled != reminder.enabled
        }
    }

    private fun insertExecutionStateIfMissing(db: SQLiteDatabase, reminderId: String) {
        val values = ContentValues().apply {
            put("reminder_id", reminderId)
            put("state", "synced_locally")
            put("updated_at", OffsetDateTime.now().toString())
        }
        db.insertWithOnConflict(
            "reminder_execution_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun resetExecutionState(db: SQLiteDatabase, reminderId: String) {
        val values = ContentValues().apply {
            put("reminder_id", reminderId)
            put("state", "synced_locally")
            putNull("scheduled_locally_at")
            putNull("triggered_at")
            putNull("played_at")
            putNull("acknowledged_at")
            putNull("failed_at")
            putNull("expired_at")
            putNull("error")
            put("updated_at", OffsetDateTime.now().toString())
        }
        db.insertWithOnConflict(
            "reminder_execution_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun upsertSyncState(
        db: SQLiteDatabase,
        deviceId: String,
        syncVersion: Long,
        serverTime: String?,
    ) {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            put("sync_version", syncVersion)
            put("server_time", serverTime)
            put("updated_at", OffsetDateTime.now().toString())
        }
        db.insertWithOnConflict(
            "reminder_sync_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun currentSyncVersion(db: SQLiteDatabase, deviceId: String): Long {
        val cursor = db.query(
            "reminder_sync_state",
            arrayOf("sync_version"),
            "device_id = ?",
            arrayOf(deviceId),
            null,
            null,
            null,
            "1",
        )
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    private fun nextCandidate(db: SQLiteDatabase, after: OffsetDateTime): ReminderCandidate? =
        enabledCandidates(db)
            .filter { candidate -> candidate.parsed > after && !candidate.isTerminal }
            .minByOrNull { candidate -> candidate.parsed }

    private fun enabledCandidates(db: SQLiteDatabase): List<ReminderCandidate> {
        val cursor = db.rawQuery(
            """
            SELECT d.id, d.title, d.message, d.scheduled_at,
                   d.audio_type, d.audio_url, d.audio_content_type, d.audio_filename,
                   s.state, s.updated_at
            FROM reminder_definitions d
            LEFT JOIN reminder_execution_state s ON s.reminder_id = d.id
            WHERE d.enabled = 1
            ORDER BY d.scheduled_at ASC
            """.trimIndent(),
            emptyArray(),
        )
        cursor.use {
            val candidates = mutableListOf<ReminderCandidate>()
            while (it.moveToNext()) {
                val scheduledAt = it.getString(3)
                val parsed = try {
                    OffsetDateTime.parse(scheduledAt)
                } catch (error: Exception) {
                    null
                } ?: continue
                candidates += ReminderCandidate(
                    reminderId = it.getString(0),
                    title = it.getString(1),
                    message = it.getString(2),
                    scheduledAt = scheduledAt,
                    audioType = it.getString(4),
                    audioUrl = it.getNullableString(5),
                    audioContentType = it.getNullableString(6),
                    audioFilename = it.getNullableString(7),
                    parsed = parsed,
                    isTerminal = isTerminalForOccurrence(
                        state = it.getString(8),
                        scheduledAt = scheduledAt,
                        stateUpdatedAt = it.getString(9),
                    ),
                )
            }
            return candidates
        }
    }

    private fun isTerminalForOccurrence(
        state: String?,
        scheduledAt: String,
        stateUpdatedAt: String?,
    ): Boolean {
        if (state != "acknowledged" && state != "failed" && state != "expired") {
            return false
        }
        val scheduled = try {
            OffsetDateTime.parse(scheduledAt)
        } catch (error: Exception) {
            return true
        }
        val stateUpdated = try {
            OffsetDateTime.parse(stateUpdatedAt)
        } catch (error: Exception) {
            return true
        }
        return !scheduled.isAfter(stateUpdated)
    }

    private data class ReminderCandidate(
        val reminderId: String,
        val title: String,
        val message: String,
        val scheduledAt: String,
        val audioType: String,
        val audioUrl: String?,
        val audioContentType: String?,
        val audioFilename: String?,
        val parsed: OffsetDateTime,
        val isTerminal: Boolean,
    ) {
        fun toReminderState(): ReminderState =
            ReminderState(
                title = title,
                message = message,
                timeText = formatReminderTime(scheduledAt),
                reminderId = reminderId,
                audioType = audioType,
                audioUrl = audioUrl,
                audioContentType = audioContentType,
                audioFilename = audioFilename,
            )
    }

    companion object {
        private const val DATABASE_NAME = "elderptod_reminders.sqlite3"
        private const val DATABASE_VERSION = 2
    }
}

private fun addColumnIfMissing(
    db: SQLiteDatabase,
    table: String,
    column: String,
    definition: String,
) {
    val cursor = db.rawQuery("PRAGMA table_info($table)", emptyArray())
    cursor.use {
        while (it.moveToNext()) {
            if (it.getString(1) == column) return
        }
    }
    db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
}

private fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)
