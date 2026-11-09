package com.videograb.app.engine

import java.net.URI

data class Variant(val url: String, val bandwidth: Long, val resolution: String?) {
    val label: String
        get() {
            val h = resolution?.substringAfter('x', "")?.takeIf { it.isNotEmpty() }?.let { "${it}p" }
            val mbps = String.format("%.1f Mbps", bandwidth / 1_000_000.0)
            return listOfNotNull(h, mbps).joinToString(" • ")
        }
}

data class MediaPlaylist(val segments: List<String>, val encrypted: Boolean, val hasInitMap: Boolean)

object Hls {
    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    private fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

    fun parseMaster(base: String, text: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val out = mutableListOf<Variant>()
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val res = Regex("RESOLUTION=(\\d+x\\d+)").find(l)?.groupValues?.get(1)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            out += Variant(resolve(base, uri), bw, res)
        }
        return out.sortedByDescending { it.bandwidth }
    }

    fun parseMedia(base: String, text: String): MediaPlaylist {
        val segs = mutableListOf<String>()
        var encrypted = false
        var init: String? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXT-X-KEY")) {
                if (!l.contains("METHOD=NONE")) encrypted = true
            } else if (l.startsWith("#EXT-X-MAP")) {
                Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { init = resolve(base, it) }
            } else if (!l.startsWith("#")) {
                segs += resolve(base, l)
            }
        }
        init?.let { segs.add(0, it) }
        return MediaPlaylist(segs, encrypted, init != null)
    }
}
