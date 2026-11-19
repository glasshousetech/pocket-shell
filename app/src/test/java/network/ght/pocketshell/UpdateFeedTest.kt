package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateFeedTest {
    private val sha = "0734184f829504b870d48a0b078b3f778561fe929393647f91da8494f4a46796"
    private fun feed(url: String, s: String = sha) =
        """{"versionCode":36,"versionName":"0.7.2","apkUrl":"$url","sha256":"$s","bytes":123}"""

    @Test fun acceptsHomeServerReleaseUrl() {
        val r = UpdateFeed.parse(feed("https://dl.ght.network/apps/pocketshell/0.7.2-abc/pocketshell-0.7.2.apk"))
        assertEquals(36, r.code); assertEquals("0.7.2", r.name); assertEquals(sha, r.sha256); assertEquals(123L, r.bytes)
    }

    @Test fun acceptsStableDashPath() {
        assertTrue(UpdateFeed.isTrustedApkUrl("https://dl.ght.network/pocketshell-0.7.2.apk"))
    }

    @Test fun refusesUntrustedLocations() {
        listOf(
            "http://dl.ght.network/apps/pocketshell/x.apk",
            "https://evil.example/apps/pocketshell/x.apk",
            "https://dl.ght.network.evil.example/apps/pocketshell/x.apk",
            "https://user@dl.ght.network/apps/pocketshell/x.apk",
            "https://dl.ght.network:8443/apps/pocketshell/x.apk",
            "https://dl.ght.network/apps/raven/x.apk",
            "https://dl.ght.network/apps/pocketshell/x.zip",
            "https://dl.ght.network/apps/pocketshell/../raven/x.apk",
            "https://dl.ght.network/apps/pocketshell/%2e%2e/raven/x.apk",
            "https://dl.ght.network/apps/pocketshell/x.apk?redirect=1",
        ).forEach { assertFalse(it, UpdateFeed.isTrustedApkUrl(it)) }
    }

    @Test(expected = IOException::class) fun refusesFeedWithoutSha() {
        UpdateFeed.parse(feed("https://dl.ght.network/apps/pocketshell/x.apk", ""))
    }

    @Test(expected = IOException::class) fun refusesFeedWithUntrustedUrl() {
        UpdateFeed.parse(feed("https://example.com/x.apk"))
    }
}
