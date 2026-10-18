package com.embasic.eqr.ui.ads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.embasic.eqr.BuildConfig
import com.embasic.eqr.core.ads.AdPlacement
import com.embasic.eqr.platform.ads.AdConsentManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun EqrAdSlot(
    placement: AdPlacement,
    modifier: Modifier = Modifier,
) {
    val consent by AdConsentManager.state.collectAsStateWithLifecycle()
    if (!BuildConfig.ADS_ENABLED || placement == AdPlacement.NONE || !consent.canRequestAds) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Reklam" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Reklam",
            modifier = Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (placement) {
            AdPlacement.COMPACT -> CompactBanner()
            AdPlacement.LARGE -> LargeBanner()
            AdPlacement.NONE -> Unit
        }
    }
}

@Composable
private fun CompactBanner() {
    val context = LocalContext.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(32)
        key(widthDp) {
            val adView = remember(widthDp) {
                AdView(context).apply {
                    adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                    setAdSize(AdSize.getInlineAdaptiveBannerAdSize(widthDp, 50))
                    loadAd(AdRequest.Builder().build())
                }
            }

            DisposableEffect(adView) {
                onDispose { adView.destroy() }
            }

            AndroidView(
                factory = { adView },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LargeBanner() {
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_LARGE_BANNER_UNIT_ID
            setAdSize(AdSize.MEDIUM_RECTANGLE)
            loadAd(AdRequest.Builder().build())
        }
    }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    Box(contentAlignment = Alignment.Center) {
        AndroidView(factory = { adView })
    }
}
