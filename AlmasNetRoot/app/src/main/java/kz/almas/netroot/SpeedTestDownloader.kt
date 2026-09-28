package kz.almas.netroot

import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class SpeedTestDownloader {

    companion object {
        const val TARGET_BYTES: Long = 1_000_000_000L
        private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=1000000000"
    }

    data class Progress(
        val downloadedBytes: Long,
        val percent: Int,
        val mbps: Double,
        val elapsedSeconds: Double,
        val remainingSeconds: Double?
    )

    data class Result(
        val downloadedBytes: Long,
        val averageMbps: Double,
        val elapsedSeconds: Double,
        val complete: Boolean
    )

    suspend fun run(onProgress: suspend (Progress) -> Unit): Result {
        val connection = (URL(TEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "AlmasNetRoot/0.2")
        }

        val startNs = System.nanoTime()
        var downloaded = 0L
        var lastUpdateNs = startNs

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            connection.inputStream.buffered(256 * 1024).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (downloaded < TARGET_BYTES) {
                    coroutineContext.ensureActive()
                    val maxRead = minOf(buffer.size.toLong(), TARGET_BYTES - downloaded).toInt()
                    val count = input.read(buffer, 0, maxRead)
                    if (count < 0) break
                    downloaded += count

                    val now = System.nanoTime()
                    if (now - lastUpdateNs >= 200_000_000L || downloaded >= TARGET_BYTES) {
                        val elapsed = (now - startNs) / 1_000_000_000.0
                        val mbps = if (elapsed > 0.0) downloaded * 8.0 / elapsed / 1_000_000.0 else 0.0
                        val remaining = if (mbps > 0.0) {
                            (TARGET_BYTES - downloaded) * 8.0 / (mbps * 1_000_000.0)
                        } else null
                        onProgress(
                            Progress(
                                downloadedBytes = downloaded,
                                percent = ((downloaded * 100L) / TARGET_BYTES).toInt().coerceIn(0, 100),
                                mbps = mbps,
                                elapsedSeconds = elapsed,
                                remainingSeconds = remaining
                            )
                        )
                        lastUpdateNs = now
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        val elapsed = (System.nanoTime() - startNs) / 1_000_000_000.0
        val avgMbps = if (elapsed > 0.0) downloaded * 8.0 / elapsed / 1_000_000.0 else 0.0
        return Result(downloaded, avgMbps, elapsed, downloaded >= TARGET_BYTES)
    }
}
