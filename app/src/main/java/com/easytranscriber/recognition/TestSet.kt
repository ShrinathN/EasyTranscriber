package com.easytranscriber.recognition

import java.io.File

/**
 * A folder of 16 kHz WAV files with optional reference transcripts in `trans.txt`
 * (one line per file: "<file name or base name> <reference text>").
 */
data class TestSet(val name: String, val files: List<File>, val references: Map<String, String>) {
    /** References concatenated in playback order, or null unless every file has one. */
    val combinedReference: String?
        get() = files.map { references[it.name] ?: return null }.joinToString(" ")

    companion object {
        fun load(dir: File): TestSet? {
            val wavs = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                ?.sortedBy { it.name } ?: return null
            if (wavs.isEmpty()) return null
            val refs = HashMap<String, String>()
            val trans = File(dir, "trans.txt")
            if (trans.isFile) {
                for (line in trans.readLines()) {
                    val key = line.substringBefore(' ').trim()
                    val text = line.substringAfter(' ', "").trim()
                    if (key.isEmpty() || text.isEmpty()) continue
                    val name = if (key.endsWith(".wav", ignoreCase = true)) key else "$key.wav"
                    refs[name] = text
                }
            }
            return TestSet(dir.name, wavs, refs)
        }

        fun loadAll(root: File): List<TestSet> =
            root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.mapNotNull(::load).orEmpty()
    }
}
