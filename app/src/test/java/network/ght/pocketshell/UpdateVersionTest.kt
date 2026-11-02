package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionTest {
    @Test fun reviewBuildNeverSuggestsAnOlderStableRelease() {
        assertTrue(UpdateChecker.compareVersions("0.5.4-review.1", "0.5.3") > 0)
        assertTrue(UpdateChecker.compareVersions("0.5.4-review.1", "0.5.4") < 0)
        assertTrue(UpdateChecker.compareVersions("0.5.9", "0.5.10") < 0)
        assertEquals(0, UpdateChecker.compareVersions("0.5.3", "v0.5.3"))
    }
    @Test(expected = IllegalStateException::class)
    fun malformedTagIsAnErrorInsteadOfAnUpdateDecision() { UpdateChecker.compareVersions("0.5.3", "unknown") }
}
