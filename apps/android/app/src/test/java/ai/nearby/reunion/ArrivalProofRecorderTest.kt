package ai.nearby.reunion

import org.junit.Assert.*
import org.junit.Test

class ArrivalProofRecorderTest {
    private class Fixture {
        var now = 100L
        val lines = mutableListOf<String>()
        val proof = ArrivalProofRecorder({ now }) { lines.add(it) }
    }
    @Test fun arrival_requires_positive_new_sequence() {
        val f = Fixture()
        listOf(-1L, 0L, 1L, 1L, 0L, 2L).forEach(f.proof::sdkArrival)
        assertEquals(listOf("event=sdk_arrival seq=1 elapsedMs=100", "event=sdk_arrival seq=2 elapsedMs=100"), f.lines)
    }
    @Test fun an_unchanged_vehicle_poll_is_deduplicated() {
        val f = Fixture()
        repeat(10) { f.proof.gate(1, true, true, true, true, true); f.now++ }
        assertEquals(1, f.lines.size)
        f.proof.gate(1, true, true, true, false, false)
        assertEquals(2, f.lines.size)
    }
    @Test fun unknown_signals_are_explicit_and_do_not_look_confirmed() {
        val f = Fixture(); f.proof.gate(1, null, null, null, false, false)
        assertTrue(f.lines.single().contains("parkedKnown=false parked=false speedKnown=false zeroSpeed=false uxKnown=false uxAllowed=false"))
    }
    @Test fun success_requires_all_native_visibility_and_live_guards() {
        for (missing in 0..5) {
            val f = Fixture()
            f.proof.ui(if (missing == 0) 0 else 1, missing != 1, missing != 2, missing != 3, missing != 4, true, missing != 5)
            assertFalse(f.lines.any { it.contains("nudgeVisible=true") || it.contains("photoDecoded=true") })
        }
    }
    @Test fun fallback_and_decoded_public_photo_remain_distinct() {
        val f = Fixture()
        f.proof.ui(1, true, true, true, true, false, true)
        assertTrue(f.lines.last().contains("photoDecoded=false nudgeVisible=true"))
        f.proof.ui(1, true, true, true, true, true, true)
        assertTrue(f.lines.last().contains("photoDecoded=true nudgeVisible=true"))
    }
    @Test fun same_ui_report_is_deduplicated() {
        val f = Fixture()
        repeat(10) { f.proof.ui(1, true, true, true, true, true, true) }
        assertEquals(1, f.lines.size)
    }
    @Test fun hidden_or_covered_ui_cannot_report_photo_success() {
        val f = Fixture()
        f.proof.ui(1, false, true, true, true, true, true)
        assertTrue(f.lines.single().contains("photoDecoded=false nudgeVisible=false"))
    }
    @Test fun record_rate_is_bounded_and_recovers_after_one_minute() {
        val f = Fixture()
        for (i in 1..100) f.proof.sdkArrival(i.toLong())
        assertEquals(60, f.lines.size)
        f.now += 60_000
        f.proof.sdkArrival(101)
        assertEquals(61, f.lines.size)
    }
    @Test fun noisy_gate_samples_cannot_consume_sdk_callback_budget() {
        val f = Fixture()
        for (i in 1..100) f.proof.gate(i.toLong(), true, true, true, true, true)
        assertEquals(60, f.lines.size)
        f.proof.sdkArrival(101)
        assertEquals(61, f.lines.size)
        assertTrue(f.lines.last().startsWith("event=sdk_arrival seq=101"))
    }
    @Test fun logging_failure_cannot_interrupt_a_callback() {
        ArrivalProofRecorder({ 1 }) { throw IllegalStateException() }.sdkArrival(1)
    }
    @Test fun visible_paused_window_is_observable_without_changing_its_permission() {
        val f = Fixture(); f.proof.activity(1, false, true, true, false)
        assertEquals("event=activity seq=1 resumed=false visible=true multiWindow=true stopped=false elapsedMs=100", f.lines.single())
    }
    @Test fun output_alphabet_excludes_free_text_and_identifiers() {
        val f = Fixture()
        f.proof.sdkArrival(1); f.proof.gate(1, true, true, true, true, true)
        f.proof.ui(1, true, true, true, true, true, true)
        f.proof.activity(1, true, true, true, false)
        val field = Regex("(?:event=(?:sdk_arrival|vehicle_gate|ui|activity)|[a-zA-Z]+=(?:true|false|[0-9]+))")
        for (line in f.lines) for (token in line.split(" ")) assertTrue(token, field.matches(token))
    }
}
