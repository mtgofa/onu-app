package com.hg8145v5.manager.data

import android.content.Context

class StatsStore(context: Context) {
    private val p = context.getSharedPreferences("hg8145v5_usage", Context.MODE_PRIVATE)
    private val dayMs = 86_400_000L
    private val window = 30L

    fun tick(downNow: Long, upNow: Long): Pair<Long, Long> {
        val today = nowDay()
        val lastDown = p.getLong("last_down", -1L)
        val lastUp = p.getLong("last_up", -1L)
        val lastTs = p.getLong("last_ts", 0L)
        val gap = nowMs() - lastTs
        if (lastDown >= 0 && lastUp >= 0 && gap in 0L..48L * dayMs) {
            if (downNow >= lastDown) add(today, "d", downNow - lastDown)
            if (upNow >= lastUp) add(today, "u", upNow - lastUp)
        }
        p.edit()
            .putLong("last_down", downNow)
            .putLong("last_up", upNow)
            .putLong("last_ts", nowMs())
            .apply()
        return total()
    }

    private fun add(day: Long, key: String, v: Long) {
        val k = "v_${key}_$day"
        p.edit().putLong(k, p.getLong(k, 0L) + v).apply()
    }

    private fun total(): Pair<Long, Long> {
        val cut = nowDay() - window
        var d = 0L; var u = 0L
        val e = p.all.iterator()
        while (e.hasNext()) {
            val (k, v) = e.next()
            if (!k.startsWith("v_")) continue
            val parts = k.removePrefix("v_").split('_', limit = 2)
            if (parts.size != 2) continue
            val day = parts[1].toLongOrNull() ?: continue
            if (day <= cut) {
                p.edit().remove(k).apply(); continue
            }
            val n = (v as? Long) ?: 0L
            if (parts[0] == "d") d += n else if (parts[0] == "u") u += n
        }
        return d to u
    }

    fun clear() = p.edit().clear().apply()

    private fun nowMs() = System.currentTimeMillis()
    private fun nowDay() = nowMs() / dayMs
}
