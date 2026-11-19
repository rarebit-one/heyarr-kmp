package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.power.ProcessSleepInhibitor
import one.rarebit.heyarr.desktop.power.SleepInhibitor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The hold is a real child process here (`cat`, a stand-in for the platform's holder). */
class SleepInhibitorTest {
    @Test
    fun aHoldRunsUntilClosedAndItsChildEndsWithIt() {
        var child: Process? = null
        val inhibitor = ProcessSleepInhibitor({ listOf("cat") }) { command ->
            ProcessBuilder(command).start().also { child = it }
        }
        val hold = assertNotNull(inhibitor.acquire("Casting a film"))
        val running = assertNotNull(child)
        assertTrue(running.isAlive, "The lock lasts while its holder runs")
        hold.close()
        assertTrue(running.waitFor(5, TimeUnit.SECONDS), "Closing the hold ends the holder")
        assertFalse(running.isAlive)
    }

    @Test
    fun aHolderThatIsNotInstalledMeansNoHoldNotAFailure() {
        val inhibitor = ProcessSleepInhibitor({ listOf("heyarr-no-such-inhibitor-${System.nanoTime()}") })
        assertNull(inhibitor.acquire("Casting a film"))
    }

    @Test
    fun linuxTakesALogindBlockOnSleepAndIdleThatEndsWithThisProcess() {
        val command = SleepInhibitor.logindCommand("Casting Film to TV")
        assertEquals("systemd-inhibit", command.first())
        assertTrue("--what=sleep:idle" in command)
        assertTrue("--mode=block" in command)
        assertTrue("--why=Casting Film to TV" in command)
        // The child waits on a pipe from this JVM, so a crash or kill releases the lock too.
        assertEquals("cat", command.last())
    }

    @Test
    fun macOsAssertsAgainstIdleSleepUntilThisProcessExits() {
        val command = SleepInhibitor.caffeinateCommand()
        assertEquals("caffeinate", command.first())
        assertTrue("-i" in command)
        assertEquals(listOf("-w", ProcessHandle.current().pid().toString()), command.takeLast(2))
    }

    @Test
    fun eachOsGetsItsInhibitorAndOthersNone() {
        assertTrue(SleepInhibitor.system("Linux") is ProcessSleepInhibitor)
        assertTrue(SleepInhibitor.system("Mac OS X") is ProcessSleepInhibitor)
        assertSame(SleepInhibitor.NONE, SleepInhibitor.system("Windows 11"))
        assertNull(SleepInhibitor.NONE.acquire("anything"))
    }
}
