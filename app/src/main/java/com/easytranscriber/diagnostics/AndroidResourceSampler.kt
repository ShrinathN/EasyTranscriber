package com.easytranscriber.diagnostics

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import com.easytranscriber.pipeline.ResourceSampler
import java.io.File

/** Process PSS (Debug.getPss) and CPU time (utime+stime from /proc/self/stat). */
class AndroidResourceSampler : ResourceSampler {
    private val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L

    override fun sample(): Pair<Long, Long> = Debug.getPss() to cpuTimeMs()

    private fun cpuTimeMs(): Long {
        val stat = File("/proc/self/stat").readText()
        // Fields after "(comm) " start at field 3 (state); utime is field 14, stime field 15.
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        val ticks = fields[11].toLong() + fields[12].toLong()
        return ticks * 1000 / ticksPerSecond
    }
}
