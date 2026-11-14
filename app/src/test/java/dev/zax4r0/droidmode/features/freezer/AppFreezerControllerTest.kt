package dev.zax4r0.droidmode.features.freezer

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFreezerControllerTest {
    @Test fun bulkActionFreezesWhenAnySelectedAppIsActive() {
        assertEquals(BulkFreezeAction.FREEZE, AppFreezerController.nextBulkAction(3, 2))
    }

    @Test fun bulkActionUnfreezesWhenAllSelectedAppsAreFrozen() {
        assertEquals(BulkFreezeAction.UNFREEZE, AppFreezerController.nextBulkAction(3, 3))
    }

    @Test fun bulkActionDoesNothingWithoutSelection() {
        assertEquals(BulkFreezeAction.NONE, AppFreezerController.nextBulkAction(0, 0))
    }
}
