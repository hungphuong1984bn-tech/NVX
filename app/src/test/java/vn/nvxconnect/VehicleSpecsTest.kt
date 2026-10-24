package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.KnownUuids
import vn.nvxconnect.domain.*

class VehicleSpecsTest {
    @Test fun rangeFromTankAndConsumption() {
        val e = RangeCalc.estimate(5.5, 2.5)!!
        assertEquals(220, e.rangeKm)
    }
    @Test fun rangeRejectsMissingOrAbsurdInput() {
        assertNull(RangeCalc.estimate(5.5, null)); assertNull(RangeCalc.estimate(5.5, 0.0))
        assertNull(RangeCalc.estimate(0.0, 2.5)); assertNull(RangeCalc.estimate(5.5, 2.5, 1.5))
    }
    @Test fun tankOverrideValidated() {
        assertEquals(4.6, VehicleSpecs.tankOrDefault(4.6), 0.0)
        assertEquals(VehicleSpecs.DEFAULT_TANK_L, VehicleSpecs.tankOrDefault(99.0), 0.0)
        assertEquals(VehicleSpecs.DEFAULT_TANK_L, VehicleSpecs.tankOrDefault(null), 0.0)
    }
    @Test fun specsNeverClaimLiveData() {
        assertTrue(VehicleSpecs.nvx155.isNotEmpty()); assertTrue(VehicleSpecs.DISCLAIMER.contains("không đọc"))
        assertEquals(6, DataSource.values().size)
    }
    @Test fun logUuidsGetGenericLabels() {
        assertNotNull(KnownUuids.label("00001812-0000-1000-8000-00805f9b34fb"))
        assertNotNull(KnownUuids.label(KnownUuids.TI_SERIAL_SERVICE))
        assertNull(KnownUuids.label("e7810bd2-73ae-499d-8c15-faa9aef0c3f2"))
    }
}
