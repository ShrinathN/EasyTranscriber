package com.easytranscriber.models

import android.os.StatFs
import com.easytranscriber.diagnostics.EventLog
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.ModelEntry
import com.easytranscriber.recognition.TranscriptionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed interface DownloadState {
    data class Running(val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Failed(val message: String) : DownloadState
}

/**
 * Downloads models on explicit user request. This is the app's only network use; it never sends
 * audio or text. Record-first models also fetch the Silero speech detector if it's missing.
 */
class ModelDownloader(
    private val modelsDir: File,
    private val scope: CoroutineScope,
    private val log: EventLog,
    private val onInstalled: () -> Unit,
) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()
    private val jobs = HashMap<String, Job>()

    init {
        ModelInstaller.cleanStaging(modelsDir)
    }

    fun download(entry: ModelEntry) {
        synchronized(jobs) {
            if (jobs[entry.id]?.isActive == true) return
            jobs[entry.id] = scope.launch(Dispatchers.IO) { run(entry) }
        }
    }

    fun cancel(id: String) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        set(id, null)
    }

    fun dismissError(id: String) = set(id, null)

    private suspend fun run(entry: ModelEntry) {
        val needVad = TranscriptionMode.RECORD_THEN_TRANSCRIBE in entry.modes && !vadInstalled()
        val total = entry.downloadBytes + if (needVad) ModelCatalog.VAD_BYTES else 0
        try {
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < total + 50_000_000) {
                throw IOException("Not enough free storage (need about ${(total + 50_000_000) / 1_000_000} MB, ${free / 1_000_000} MB free)")
            }
            set(entry.id, DownloadState.Running(0, total))
            log.add("Model download started: ${entry.id}")
            var base = 0L
            if (needVad) {
                fetch(ModelCatalog.VAD_URL, entry.id, base, total) { input, cancelled ->
                    ModelInstaller.installFile(input, modelsDir, ModelCatalog.VAD_DIR, ModelCatalog.VAD_FILE, ModelCatalog.VAD_SHA256, cancelled)
                }
                base += ModelCatalog.VAD_BYTES
            }
            if (entry.fileUrls.isEmpty()) {
                fetch(entry.archiveUrl, entry.id, base, total) { input, cancelled ->
                    ModelInstaller.installArchive(input, entry, modelsDir, cancelled)
                }
            } else {
                ModelInstaller.installFiles(entry, modelsDir) { name, install ->
                    base += fetch(entry.fileUrls.getValue(name), entry.id, base, total, install)
                }
            }
            set(entry.id, null)
            log.add("Model installed: ${entry.id}")
            onInstalled()
        } catch (e: InstallCancelledException) {
            set(entry.id, null)
            log.add("Model download cancelled: ${entry.id}")
        } catch (e: IntegrityException) {
            set(entry.id, DownloadState.Failed("The downloaded files didn't match the expected model. Nothing was installed. Try again later."))
            log.add("Model download failed verification: ${entry.id}: ${e.message}")
        } catch (e: IOException) {
            set(entry.id, DownloadState.Failed(e.message?.takeIf { it.startsWith("Not enough") }
                ?: "Download failed. Check your internet connection and try again."))
            log.add("Model download failed: ${entry.id}: ${e.message}")
        } finally {
            synchronized(jobs) { jobs.remove(entry.id) }
        }
    }

    /** Streams [url] into [install], reporting progress from [base]; returns the bytes read. */
    private suspend fun fetch(
        url: String,
        id: String,
        base: Long,
        total: Long,
        install: (InputStream, () -> Boolean) -> Unit,
    ): Long {
        val ctx = coroutineContext
        val cancelled = { !ctx.isActive }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "EasyTranscriber")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            var lastReport = 0L
            val counting = object : FilterInputStream(conn.inputStream) {
                var count = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        count += n
                        if (count - lastReport > 512 * 1024) {
                            lastReport = count
                            set(id, DownloadState.Running(base + count, total))
                        }
                    }
                    return n
                }
            }
            counting.use { install(it, cancelled) }
            return counting.count
        } finally {
            conn.disconnect()
        }
    }

    private fun vadInstalled() = ModelCatalog.vadFile(modelsDir).let { it.isFile && it.length() > 0 }

    private fun set(id: String, state: DownloadState?) {
        _states.update { if (state == null) it - id else it + (id to state) }
    }
}
