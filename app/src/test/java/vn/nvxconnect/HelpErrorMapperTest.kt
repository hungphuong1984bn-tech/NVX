package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.auto.AutoFailure
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.helpcenter.HelpContent.Id
import vn.nvxconnect.helpcenter.HelpErrorMapper
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.protocol.VehicleLinkStatus

class HelpErrorMapperTest {
    private val none = VehicleLinkStatus(LinkLevel.NOT_CONNECTED, "CHƯA KẾT NỐI", null, false)
    private val raw = VehicleLinkStatus(LinkLevel.RAW_DATA_ONLY, VehicleLinkStatus.RAW_ONLY, VehicleLinkStatus.NOT_VERIFIED, false)
    private val ok = VehicleLinkStatus(LinkLevel.VERIFIED, "CONNECTED — t", null, true)

    @Test fun everyErrorKindMapsToAnExistingGuide() = ErrorKind.entries.forEach { assertNotNull(it.name, HelpContent.find(HelpErrorMapper.forError(it))) }
    @Test fun everyAutoFailureMapsToAnExistingGuide() = AutoFailure.entries.forEach { assertNotNull(it.name, HelpContent.find(HelpErrorMapper.forAutoFailure(it))) }

    @Test fun keyMappingsAreSpecific() {
        assertEquals(Id.PERMISSIONS, HelpErrorMapper.forError(ErrorKind.PERMISSION_DENIED))
        assertEquals(Id.PREPARE_PHONE, HelpErrorMapper.forError(ErrorKind.BLUETOOTH_OFF))
        assertEquals(Id.PREPARE_PHONE, HelpErrorMapper.forError(ErrorKind.LOCATION_SERVICES_OFF))
        assertEquals(Id.GATT_SERVICES, HelpErrorMapper.forError(ErrorKind.SERVICE_DISCOVERY_FAILED))
        assertEquals(Id.NOT_FOUND, HelpErrorMapper.forError(ErrorKind.SCAN_FAILED))
        assertEquals(Id.YCONNECT, HelpErrorMapper.forAutoFailure(AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED))
        assertEquals(Id.NOT_FOUND, HelpErrorMapper.forAutoFailure(AutoFailure.NOT_FOUND))
        assertEquals(Id.LOST_NO_DATA, HelpErrorMapper.forAutoFailure(AutoFailure.RETRIES_EXHAUSTED))
    }

    @Test fun stateMappingFollowsTheActualState() {
        assertEquals(Id.PERMISSIONS, HelpErrorMapper.forState(ConnState.Error(ErrorKind.PERMISSION_DENIED, "x"), none))
        assertEquals(Id.LOST_NO_DATA, HelpErrorMapper.forState(ConnState.Reconnecting(1, 3), none))
        assertEquals(Id.VEHICLE_DATA, HelpErrorMapper.forState(ConnState.Ready(emptyList()), raw))
    }

    @Test fun noFixButtonWhenThereIsNothingToFix() {
        assertNull(HelpErrorMapper.forState(ConnState.Disconnected, none))
        assertNull(HelpErrorMapper.forState(ConnState.Connecting, none))
        assertNull(HelpErrorMapper.forState(ConnState.Ready(emptyList()), ok))
    }
}
