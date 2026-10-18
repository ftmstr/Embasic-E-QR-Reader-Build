package com.embasic.eqr.core.ads

/**
 * Keeps monetization decisions out of scan, payload and security modules.
 *
 * Camera and scan-result surfaces are always ad-free. Other app surfaces may opt into a
 * compact or large placement depending on available space.
 */
enum class AppSurface {
    SCAN_CAMERA,
    SCAN_RESULT,
    HISTORY_EMPTY,
    HISTORY_LIST,
    HISTORY_DETAIL,
    GENERATOR_HOME,
    GENERATOR_FORM,
    GENERATOR_PREVIEW,
    SETTINGS,
}

enum class AdPlacement {
    NONE,
    COMPACT,
    LARGE,
}

object AdPlacementPolicy {
    fun placementFor(surface: AppSurface): AdPlacement = when (surface) {
        AppSurface.SCAN_CAMERA,
        AppSurface.SCAN_RESULT -> AdPlacement.NONE

        AppSurface.HISTORY_EMPTY,
        AppSurface.GENERATOR_HOME -> AdPlacement.LARGE

        AppSurface.HISTORY_LIST,
        AppSurface.HISTORY_DETAIL,
        AppSurface.GENERATOR_FORM,
        AppSurface.GENERATOR_PREVIEW,
        AppSurface.SETTINGS -> AdPlacement.COMPACT
    }
}
