package com.easytranscriber.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.TranscriptionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class TypingSpeed(val interReportDelayMs: Long, val label: String) {
    CAREFUL(20, "Careful (~25 chars/s)"),
    NORMAL(10, "Normal (~35 chars/s)"),
    FAST(5, "Fast (~60 chars/s)"),
}

/** How long the last recognized word must stay unchanged before it is typed (live typing). */
enum class CommitDelay(val stableMs: Long?, val label: String, val description: String) {
    FAST(120, "Fast", "Words appear sooner; occasionally a word is typed in two parts."),
    BALANCED(300, "Balanced", "Short wait so most words are complete before typing."),
    CAUTIOUS(null, "Cautious", "Each word waits until the next word starts or you pause."),
}

data class AppSettings(
    val mode: TranscriptionMode = TranscriptionMode.REAL_TIME,
    val streamingModelId: String = ModelCatalog.forMode(TranscriptionMode.REAL_TIME).first().id,
    val batchModelId: String = ModelCatalog.forMode(TranscriptionMode.RECORD_THEN_TRANSCRIBE).first().id,
    /** Whisper language code; "" = detect. Ignored by single-language models. */
    val batchLanguage: String = "en",
    val reviewBeforeSending: Boolean = true,
    /** Record first: stop recording and start transcribing once the speaker pauses. */
    val autoStopOnPause: Boolean = true,
    /** Record first: how long a pause (after speech) ends the recording. */
    val pauseToStopMs: Int = 2_000,
    /** Record first, with [autoStopOnPause]: after a pause, type the phrase and keep listening until stopped. */
    val keepListening: Boolean = false,
    val spokenPunctuation: Boolean = true,
    val autoCapitalize: Boolean = true,
    val spaceBetweenDictations: Boolean = true,
    val typingSpeed: TypingSpeed = TypingSpeed.NORMAL,
    val commitDelay: CommitDelay = CommitDelay.FAST,
    val threads: Int = 2,
    val maxRecordingMinutes: Int = 10,
    val autoStartKeyboard: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists [AppSettings] with Jetpack DataStore; unknown/invalid stored values fall back to defaults. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val STREAMING_MODEL = stringPreferencesKey("streaming_model")
        val BATCH_MODEL = stringPreferencesKey("batch_model")
        val BATCH_LANGUAGE = stringPreferencesKey("batch_language")
        val REVIEW = booleanPreferencesKey("review_before_sending")
        val AUTO_STOP = booleanPreferencesKey("auto_stop_on_pause")
        val PAUSE_TO_STOP = intPreferencesKey("pause_to_stop_ms")
        val KEEP_LISTENING = booleanPreferencesKey("keep_listening")
        val SPOKEN_PUNCTUATION = booleanPreferencesKey("spoken_punctuation")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val SPACE_BETWEEN = booleanPreferencesKey("space_between_dictations")
        val TYPING_SPEED = stringPreferencesKey("typing_speed")
        val COMMIT_DELAY = stringPreferencesKey("commit_delay")
        val THREADS = intPreferencesKey("threads")
        val MAX_RECORDING_MIN = intPreferencesKey("max_recording_minutes")
        val AUTO_START_KEYBOARD = booleanPreferencesKey("auto_start_keyboard")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::fromPrefs)

    private fun validFor(id: String, mode: TranscriptionMode) = ModelCatalog.byId(id)?.modes?.contains(mode) == true

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.MODE] = next.mode.name
            p[Keys.STREAMING_MODEL] = next.streamingModelId
            p[Keys.BATCH_MODEL] = next.batchModelId
            p[Keys.BATCH_LANGUAGE] = next.batchLanguage
            p[Keys.REVIEW] = next.reviewBeforeSending
            p[Keys.AUTO_STOP] = next.autoStopOnPause
            p[Keys.PAUSE_TO_STOP] = next.pauseToStopMs
            p[Keys.KEEP_LISTENING] = next.keepListening
            p[Keys.SPOKEN_PUNCTUATION] = next.spokenPunctuation
            p[Keys.AUTO_CAPITALIZE] = next.autoCapitalize
            p[Keys.SPACE_BETWEEN] = next.spaceBetweenDictations
            p[Keys.TYPING_SPEED] = next.typingSpeed.name
            p[Keys.COMMIT_DELAY] = next.commitDelay.name
            p[Keys.THREADS] = next.threads
            p[Keys.MAX_RECORDING_MIN] = next.maxRecordingMinutes
            p[Keys.AUTO_START_KEYBOARD] = next.autoStartKeyboard
        }
    }

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            mode = p[Keys.MODE]?.let { runCatching { TranscriptionMode.valueOf(it) }.getOrNull() } ?: d.mode,
            streamingModelId = p[Keys.STREAMING_MODEL]?.takeIf { validFor(it, TranscriptionMode.REAL_TIME) } ?: d.streamingModelId,
            batchModelId = p[Keys.BATCH_MODEL]?.takeIf { validFor(it, TranscriptionMode.RECORD_THEN_TRANSCRIBE) } ?: d.batchModelId,
            batchLanguage = p[Keys.BATCH_LANGUAGE] ?: d.batchLanguage,
            reviewBeforeSending = p[Keys.REVIEW] ?: d.reviewBeforeSending,
            autoStopOnPause = p[Keys.AUTO_STOP] ?: d.autoStopOnPause,
            pauseToStopMs = p[Keys.PAUSE_TO_STOP]?.takeIf { it in 500..10_000 } ?: d.pauseToStopMs,
            keepListening = p[Keys.KEEP_LISTENING] ?: d.keepListening,
            spokenPunctuation = p[Keys.SPOKEN_PUNCTUATION] ?: d.spokenPunctuation,
            autoCapitalize = p[Keys.AUTO_CAPITALIZE] ?: d.autoCapitalize,
            spaceBetweenDictations = p[Keys.SPACE_BETWEEN] ?: d.spaceBetweenDictations,
            typingSpeed = p[Keys.TYPING_SPEED]?.let { runCatching { TypingSpeed.valueOf(it) }.getOrNull() } ?: d.typingSpeed,
            commitDelay = p[Keys.COMMIT_DELAY]?.let { runCatching { CommitDelay.valueOf(it) }.getOrNull() } ?: d.commitDelay,
            threads = p[Keys.THREADS]?.takeIf { it in 1..4 } ?: d.threads,
            maxRecordingMinutes = p[Keys.MAX_RECORDING_MIN]?.takeIf { it in 1..60 } ?: d.maxRecordingMinutes,
            autoStartKeyboard = p[Keys.AUTO_START_KEYBOARD] ?: d.autoStartKeyboard,
        )
    }
}
