package ai.nearby.reunion

import org.junit.Assert.*
import org.junit.Test

class VehicleSampleRecorderTest {
    private class Fixture {
        var now = 100L
        val lines = mutableListOf<String>()
        val samples = VehicleSampleRecorder({ now }) { lines.add(it) }
    }
    @Test fun no_sample_before_user_started_session() {
        val f=Fixture(); f.samples.acceptedRead(0, 100, 1f, false); f.samples.unavailable(0)
        assertTrue(f.lines.isEmpty())
    }
    @Test fun equal_speed_from_new_sdk_reads_remains_a_heartbeat() {
        val f=Fixture(); f.samples.start(); f.samples.acceptedRead(0,100,1f,false)
        f.now=2100; f.samples.acceptedRead(0,2100,1f,false)
        assertEquals(2,f.lines.size); assertTrue(f.lines.last().contains("elapsedMs=2100 speedMps=1.0"))
    }
    @Test fun repeated_old_measurement_is_not_relabelled_as_fresh() {
        val f=Fixture(); f.samples.start(); f.samples.acceptedRead(0,100,1f,false)
        f.now=2100; f.samples.acceptedRead(0,100,1f,false)
        assertEquals(1,f.lines.size)
    }
    @Test fun at_most_one_sample_per_second() {
        val f=Fixture(); f.samples.start(); f.samples.acceptedRead(0,100,1f,false)
        f.now=200; f.samples.acceptedRead(0,200,2f,false)
        f.now=1100; f.samples.acceptedRead(0,1100,3f,false)
        assertEquals(2,f.lines.size)
    }
    @Test fun stale_speed_is_unknown_not_zero() {
        val f=Fixture(); f.samples.start(); f.now=4100; f.samples.acceptedRead(0,100,0f,true)
        assertEquals("event=vehicle_sample seq=0 elapsedMs=4100 speedMps=unknown parked=false fresh=false",f.lines.single())
    }
    @Test fun unavailable_speed_gear_and_nonfinite_values_are_invalid() {
        for (speed in listOf<Float?>(null,Float.NaN,Float.POSITIVE_INFINITY)) {
            val f=Fixture(); f.samples.start(); f.samples.acceptedRead(0,100,speed,true)
            assertTrue(f.lines.single().contains("speedMps=unknown"))
        }
        val f=Fixture(); f.samples.start(); f.samples.acceptedRead(0,100,1f,null)
        assertTrue(f.lines.single().contains("fresh=false"))
    }
    @Test fun unknown_is_deduplicated_but_recovery_is_observable() {
        val f=Fixture(); f.samples.start(); f.samples.unavailable(0)
        f.now=2100; f.samples.unavailable(0)
        assertEquals(1,f.lines.size)
        f.samples.acceptedRead(0,2100,0f,true)
        assertEquals(2,f.lines.size); assertTrue(f.lines.last().endsWith("parked=true fresh=true"))
    }
    @Test fun samples_expire_after_five_minutes() {
        val f=Fixture(); f.samples.start(); f.now=300100; f.samples.acceptedRead(0,f.now,1f,false)
        assertTrue(f.lines.single().contains("event=vehicle_sample_end")); assertTrue(f.lines.single().contains("expired=true"))
        f.now+=2000; f.samples.acceptedRead(0,f.now,1f,false); assertEquals(1,f.lines.size)
    }
    @Test fun explicit_stop_disables_further_samples() {
        val f=Fixture(); f.samples.start(); f.samples.stop(1); f.now=2100; f.samples.acceptedRead(1,2100,0f,true)
        assertTrue(f.lines.single().contains("stopped=true fresh=false"))
    }
    @Test fun measurement_clock_and_rounding_are_explicit() {
        val f=Fixture(); f.samples.start(); f.now=300; f.samples.acceptedRead(1,200,1.234567f,false)
        assertTrue(f.lines.single().contains("elapsedMs=200 speedMps=1.235"))
    }
    @Test fun backward_clock_ends_collection_instead_of_fabricating_age() {
        val f=Fixture(); f.samples.start(); f.now=1; f.samples.acceptedRead(1,1,0f,true)
        assertTrue(f.lines.single().contains("expired=true")); assertFalse(f.lines.single().contains("fresh=true"))
    }
    @Test fun logging_failure_never_alters_sdk_processing() {
        val samples=VehicleSampleRecorder({100}) { throw IllegalStateException() }; samples.start(); samples.acceptedRead(0,100,0f,true)
    }
}
