package dev.wledambilight.core.data

import dev.wledambilight.core.model.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRepositoryTest {

    @Test
    fun `journal is bounded and export contains no screen data`() {
        val repository = DiagnosticsRepository(clock = { 1_700_000_000_000 }, maxEvents = 3)
        repeat(5) { index ->
            repository.report(DiagnosticCode.HTTP_PROBE_OK, "detail $index")
        }
        assertEquals(3, repository.events.value.size)
        assertEquals("detail 4", repository.events.value.last().detail)

        val report = repository.exportText("0.1.0 (1)", "Desk 192.168.1.50")
        assertTrue(report.contains("HTTP_PROBE_OK"))
        assertTrue(report.contains("Desk 192.168.1.50"))
        assertFalse(report.contains("frame"))
        assertFalse(report.contains("password"))
    }

    @Test
    fun `clear removes every event`() {
        val repository = DiagnosticsRepository()
        repository.report(DiagnosticCode.SESSION_STARTED)
        repository.clear()
        assertTrue(repository.events.value.isEmpty())
    }
}
