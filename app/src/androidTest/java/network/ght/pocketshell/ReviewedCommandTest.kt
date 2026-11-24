package network.ght.pocketshell

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ReviewedCommandTest {
    @get:Rule val serviceRule = ServiceTestRule()
    @Test fun refusesWrongTabAndUnsafeMultilineWithoutRewritingTheSuggestion() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val service = (serviceRule.bindService(Intent(ctx,TermService::class.java)) as TermService.LocalBinder).service
        lateinit var a: TermSession
        lateinit var b: TermSession
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            a=service.newSession(SessionMode.SYSTEM); b=service.newSession(SessionMode.SYSTEM)
            a.session.updateSize(80,24); b.session.updateSize(80,24)
        }
        fun output(tab: TermSession): String { var value=""; inst.runOnMainSync { value=tab.session.emulator.screen.transcriptText }; return value }
        fun waitFor(condition: () -> Boolean) {
            val end=System.currentTimeMillis()+8000
            while(!condition() && System.currentTimeMillis()<end) Thread.sleep(50)
            assertTrue(condition())
        }
        try {
            Thread.sleep(250)
            inst.runOnMainSync {
                assertTrue(ReviewedCommand.insert(b,a.id,"printf 'WRONG_%s_END' 'TAB'",true).isFailure)
                assertTrue(ReviewedCommand.insert(a,a.id,"printf 'AI_%s_END\\n'\n'ORIGIN'",false).isFailure)
                assertTrue(ReviewedCommand.insert(a,a.id,"printf 'BAD_%s' 'ONE'\nprintf 'BAD_%s' 'TWO'",true).isFailure)
                assertTrue(ReviewedCommand.insert(a,a.id,"echo bad\u001b[2J",false).isFailure)
            }
            Thread.sleep(300)
            assertFalse(output(a).contains("AI_ORIGIN_END"))
            assertFalse(output(a).contains("BAD_ONE"))
            assertFalse(output(b).contains("WRONG_TAB_END"))
            inst.runOnMainSync { assertTrue(ReviewedCommand.insert(a,a.id,"printf 'AI_%s_END\\n' 'ORIGIN'",false).isSuccess) }
            Thread.sleep(300)
            assertFalse(output(a).contains("AI_ORIGIN_END"))
            inst.runOnMainSync { a.session.write("\r") }
            waitFor { output(a).contains("AI_ORIGIN_END") }
            assertFalse(output(b).contains("AI_ORIGIN_END"))
            inst.runOnMainSync { assertTrue(ReviewedCommand.insert(a,a.id,"printf 'RUN_%s_END\\n' 'EXPLICIT'",true).isSuccess) }
            waitFor { output(a).contains("RUN_EXPLICIT_END") }
            inst.runOnMainSync { a.session.write("exit\r") }
            waitFor { !a.alive.value }
            inst.runOnMainSync { assertTrue(ReviewedCommand.insert(a,a.id,"echo kept",true).isFailure) }
        } finally { inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) } }
    }
}
