package com.easytranscriber.models

import com.easytranscriber.recognition.ModelCatalog
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class ModelInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, data) in files) {
                tar.putArchiveEntry(TarArchiveEntry("model-dir/$name").apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val enc = "encoder".toByteArray()
    private val dec = ByteArray(200_000) { (it % 251).toByte() }
    private val tok = "tokens".toByteArray()
    private val entry = ModelCatalog.byId("sherpa-onnx-whisper-tiny")!!.copy(
        id = "test-model",
        files = mapOf("encoder" to "e.onnx", "decoder" to "d.onnx", "tokens" to "t.txt"),
        sha256 = mapOf("e.onnx" to sha(enc), "d.onnx" to sha(dec), "t.txt" to sha(tok)),
    )

    @Test
    fun installsOnlyNeededVerifiedFiles() {
        val dir = tmp.newFolder("models")
        val data = archive("README.md" to "x".toByteArray(), "e.onnx" to enc, "big-fp32.onnx" to ByteArray(1000), "d.onnx" to dec, "t.txt" to tok)
        ModelInstaller.installArchive(ByteArrayInputStream(data), entry, dir) { false }
        val out = File(dir, "test-model")
        assertEquals(setOf("e.onnx", "d.onnx", "t.txt"), out.list()!!.toSet())
        assertTrue(File(out, "d.onnx").readBytes().contentEquals(dec))
        assertFalse(dir.list()!!.any { it.endsWith(".partial") })
    }

    @Test
    fun hashMismatchInstallsNothing() {
        val dir = tmp.newFolder("models")
        val data = archive("e.onnx" to enc, "d.onnx" to dec.copyOf().also { it[5] = 99 }, "t.txt" to tok)
        assertThrows(IntegrityException::class.java) {
            ModelInstaller.installArchive(ByteArrayInputStream(data), entry, dir) { false }
        }
        assertFalse(File(dir, "test-model").exists())
        assertFalse(dir.list()!!.any { it.endsWith(".partial") })
    }

    @Test
    fun incompleteArchiveIsRejected() {
        val dir = tmp.newFolder("models")
        val data = archive("e.onnx" to enc, "t.txt" to tok)
        assertThrows(IntegrityException::class.java) {
            ModelInstaller.installArchive(ByteArrayInputStream(data), entry, dir) { false }
        }
        assertFalse(File(dir, "test-model").exists())
    }

    @Test
    fun cancellationLeavesNoFiles() {
        val dir = tmp.newFolder("models")
        val data = archive("e.onnx" to enc, "d.onnx" to dec, "t.txt" to tok)
        assertThrows(InstallCancelledException::class.java) {
            ModelInstaller.installArchive(ByteArrayInputStream(data), entry, dir) { true }
        }
        assertFalse(File(dir, "test-model").exists())
        assertFalse(dir.list()!!.any { it.endsWith(".partial") })
    }

    @Test
    fun singleFileIsVerified() {
        val dir = tmp.newFolder("models")
        ModelInstaller.installFile(ByteArrayInputStream(enc), dir, "vad", "v.onnx", sha(enc)) { false }
        assertTrue(File(dir, "vad/v.onnx").readBytes().contentEquals(enc))
        assertThrows(IntegrityException::class.java) {
            ModelInstaller.installFile(ByteArrayInputStream(tok), dir, "vad2", "v.onnx", sha(enc)) { false }
        }
    }

    @Test
    fun catalogHasHashesForEveryFile() {
        for (e in ModelCatalog.entries) {
            assertEquals(e.id, e.files.values.toSet(), e.sha256.keys)
            val source = if (e.fileUrls.isEmpty()) e.archiveUrl.endsWith("${e.id}.tar.bz2") else e.fileUrls.keys == e.files.values.toSet()
            assertTrue(e.id, source && e.downloadBytes > 0)
        }
    }

    @Test
    fun installsSeparatelyDownloadedFiles() = runBlocking {
        val dir = tmp.newFolder("models")
        val content = mapOf("e.onnx" to enc, "d.onnx" to dec, "t.txt" to tok)
        val fetched = ArrayList<String>()
        ModelInstaller.installFiles(entry, dir) { name, install ->
            fetched += name
            install(ByteArrayInputStream(content.getValue(name))) { false }
        }
        assertEquals(listOf("e.onnx", "d.onnx", "t.txt"), fetched)
        assertTrue(File(dir, "test-model/d.onnx").readBytes().contentEquals(dec))
        assertFalse(dir.list()!!.any { it.endsWith(".partial") })
    }

    @Test
    fun separateFileMismatchInstallsNothing() {
        val dir = tmp.newFolder("models")
        assertThrows(IntegrityException::class.java) {
            runBlocking {
                ModelInstaller.installFiles(entry, dir) { name, install ->
                    install(ByteArrayInputStream(if (name == "t.txt") "tampered".toByteArray() else if (name == "e.onnx") enc else dec)) { false }
                }
            }
        }
        assertFalse(File(dir, "test-model").exists())
        assertFalse(dir.list()!!.any { it.endsWith(".partial") })
    }
}
