package com.embasic.eqr.platform.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdConsentState(
    val canRequestAds: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
)

object AdConsentManager {
    private val _state = MutableStateFlow(AdConsentState())
    val state: StateFlow<AdConsentState> = _state.asStateFlow()

    private val mobileAdsInitialized = AtomicBoolean(false)

    fun requestConsent(activity: Activity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                publishState(consentInformation)
                initializeAdsIfAllowed(activity, consentInformation)

                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    publishState(consentInformation)
                    initializeAdsIfAllowed(activity, consentInformation)
                }
            },
            {
                // A previous valid consent state may still allow ads after a refresh failure.
                publishState(consentInformation)
                initializeAdsIfAllowed(activity, consentInformation)
            },
        )
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
            publishState(consentInformation)
            initializeAdsIfAllowed(activity, consentInformation)
        }
    }

    private fun publishState(consentInformation: ConsentInformation) {
        _state.value = AdConsentState(
            canRequestAds = consentInformation.canRequestAds(),
            privacyOptionsRequired =
                consentInformation.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
        )
    }

    private fun initializeAdsIfAllowed(
        context: Context,
        consentInformation: ConsentInformation,
    ) {
        if (!consentInformation.canRequestAds()) return
        if (mobileAdsInitialized.compareAndSet(false, true)) {
            MobileAds.initialize(context.applicationContext) { }
        }
    }
}
