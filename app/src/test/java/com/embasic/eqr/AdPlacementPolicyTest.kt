package com.embasic.eqr

import com.embasic.eqr.core.ads.AdPlacement
import com.embasic.eqr.core.ads.AdPlacementPolicy
import com.embasic.eqr.core.ads.AppSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class AdPlacementPolicyTest {
    @Test
    fun cameraAndResultAreAlwaysAdFree() {
        assertEquals(AdPlacement.NONE, AdPlacementPolicy.placementFor(AppSurface.SCAN_CAMERA))
        assertEquals(AdPlacement.NONE, AdPlacementPolicy.placementFor(AppSurface.SCAN_RESULT))
    }

    @Test
    fun menuSurfacesReceiveContextualAdSizes() {
        assertEquals(AdPlacement.LARGE, AdPlacementPolicy.placementFor(AppSurface.HISTORY_EMPTY))
        assertEquals(AdPlacement.LARGE, AdPlacementPolicy.placementFor(AppSurface.GENERATOR_HOME))
        assertEquals(AdPlacement.COMPACT, AdPlacementPolicy.placementFor(AppSurface.HISTORY_LIST))
        assertEquals(AdPlacement.COMPACT, AdPlacementPolicy.placementFor(AppSurface.SETTINGS))
    }
}
