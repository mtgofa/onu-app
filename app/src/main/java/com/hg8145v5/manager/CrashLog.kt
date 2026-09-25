package com.hg8145v5.manager

import android.content.Context
import android.util.Log
import java.io.File

/** Tiny crash recorder: on crash it writes the stack trace to filesDir/crash/ and the app
 *  surfaces the last one on the login screen, so no adb/USB is needed to see what blew up. */
object CrashLog {
    private const val TAG = "OnuCrash"
    private const val DIR = "crash"
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun crashDir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun install(ctx: Context) {
        if (previous != null) return
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val f = File(crashDir(ctx), "crash-${System.currentTimeMillis()}.txt")
                f.writeText("${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}")
                Log.e(TAG, "crash -> ${f.absolutePath}", e)
                crashDir(ctx).listFiles()
                    ?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
            } catch (_: Throwable) { /* best effort */ }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The most recent crash text (first lines), or null. */
    fun lastCrash(ctx: Context): String? {
        val latest = runCatching {
            crashDir(ctx).listFiles()?.maxByOrNull { it.lastModified() }
        }.getOrNull() ?: return null
        return runCatching { latest.takeIf { it.length() < 200_000 }?.readText() }
            ?.getOrNull()?.take(700)?.takeIf { it.isNotBlank() }
    }

    fun clear(ctx: Context) {
        runCatching { crashDir(ctx).listFiles()?.forEach { it.delete() } }
    }
}