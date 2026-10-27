package com.easytranscriber.models

import com.easytranscriber.recognition.ModelEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class IntegrityException(message: String) : IOException(message)
class InstallCancelledException : IOException("Download cancelled")

/**
 * Installs model files from a release archive stream (.tar.bz2) or from separate file downloads,
 * keeping only the files the model needs and verifying each one's SHA-256 before it becomes
 * visible to the app.
 * Files are written to a hidden staging folder and moved into place only when all are verified.
 */
object ModelInstaller {
    fun installArchive(input: InputStream, entry: ModelEntry, modelsDir: File, isCancelled: () -> Boolean) {
        val wanted = entry.files.values.toSet()
        val staging = stagingDir(modelsDir, entry.id)
        try {
            val written = HashSet<String>()
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 1 shl 16))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (isCancelled()) throw InstallCancelledException()
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    if (name !in wanted || name in written) continue
                    copyVerified(tar, File(staging, name), entry.sha256[name], isCancelled)
                    written += name
                    if (written.size == wanted.size) break // the rest of the archive isn't needed
                }
            }
            val missing = wanted - written
            if (missing.isNotEmpty()) throw IntegrityException("Download is missing ${missing.joinToString()}")
            publish(staging, File(modelsDir, entry.id))
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Installs a model published as separate files. [download] fetches one file by name and hands
     * its stream to the supplied installer; the model appears only once every file is verified.
     */
    suspend fun installFiles(
        entry: ModelEntry,
        modelsDir: File,
        download: suspend (fileName: String, install: (InputStream, isCancelled: () -> Boolean) -> Unit) -> Unit,
    ) {
        val staging = stagingDir(modelsDir, entry.id)
        try {
            for (name in entry.files.values) {
                download(name) { input, isCancelled ->
                    copyVerified(BufferedInputStream(input, 1 shl 16), File(staging, name), entry.sha256[name], isCancelled)
                }
                if (!File(staging, name).isFile) throw IntegrityException("Download is missing $name")
            }
            publish(staging, File(modelsDir, entry.id))
        } finally {
            staging.deleteRecursively()
        }
    }

    fun installFile(input: InputStream, modelsDir: File, dirName: String, fileName: String, sha256: String, isCancelled: () -> Boolean) {
        val staging = stagingDir(modelsDir, dirName)
        try {
            copyVerified(BufferedInputStream(input, 1 shl 16), File(staging, fileName), sha256, isCancelled)
            publish(staging, File(modelsDir, dirName))
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Removes staging folders left by an interrupted download (e.g. the app was killed). */
    fun cleanStaging(modelsDir: File) {
        modelsDir.listFiles { f -> f.isDirectory && f.name.startsWith(".") && f.name.endsWith(".partial") }
            ?.forEach { it.deleteRecursively() }
    }

    private fun stagingDir(modelsDir: File, id: String) =
        File(modelsDir, ".$id.partial").apply { deleteRecursively(); mkdirs() }

    private fun copyVerified(input: InputStream, dest: File, expected: String?, isCancelled: () -> Boolean) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        dest.outputStream().use { out ->
            while (true) {
                if (isCancelled()) throw InstallCancelledException()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected == null || !expected.equals(actual, ignoreCase = true)) {
            dest.delete()
            throw IntegrityException("${dest.name} failed verification (unexpected content)")
        }
    }

    /** Moves verified files into the model folder, replacing older copies. */
    private fun publish(staging: File, target: File) {
        target.mkdirs()
        staging.listFiles()?.forEach { f ->
            val dest = File(target, f.name)
            dest.delete()
            if (!f.renameTo(dest)) throw IOException("Could not move ${f.name} into place")
        }
    }
}
