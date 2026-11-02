package network.ght.pocketshell

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pocket Shell is sideloaded (dl.ght.network), not Play Store, so nothing
 * auto-updates it — this is the only signal a user gets that a newer build
 * exists. Reads the latest GitHub Release tag directly (public repo, no auth
 * needed) rather than pinging a GHT-run endpoint that would need to be kept
 * in sync separately.
 */
object UpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/glasshousetech/pocket-shell/releases/latest"
    const val DOWNLOAD_URL = "https://dl.ght.network/pocketshell.apk"

    data class Result(val currentVersion: String, val latestVersion: String, val isUpdateAvailable: Boolean)

    /** Runs a blocking network call — invoke from a background dispatcher. */
    fun check(currentVersion: String): kotlin.Result<Result> = runCatching {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val tag = JSONObject(body).getString("tag_name").removePrefix("v")
        Result(
            currentVersion = currentVersion,
            latestVersion = tag,
            isUpdateAvailable = compareVersions(currentVersion, tag) < 0,
        )
    }

    /** Compare numeric releases without misreading a review suffix as a zero patch number. */
    internal fun compareVersions(a: String, b: String): Int {
        fun core(value: String) = value.removePrefix("v").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: error("Unrecognized release version.") }
        val ax = core(a)
        val bx = core(b)
        for (i in 0 until maxOf(ax.size, bx.size)) {
            val diff = ax.getOrElse(i) { 0 }.compareTo(bx.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        if (a.contains('-') && !b.contains('-')) return -1
        if (!a.contains('-') && b.contains('-')) return 1
        return 0
    }
}
