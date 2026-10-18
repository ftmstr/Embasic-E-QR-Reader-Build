package com.embasic.eqr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embasic.eqr.data.settings.AppSettings
import com.embasic.eqr.platform.ads.AdConsentManager
import com.embasic.eqr.platform.input.ExternalInput
import com.embasic.eqr.platform.input.parseExternalInput
import com.embasic.eqr.ui.EqrRoot
import com.embasic.eqr.ui.theme.EqrTheme

class MainActivity : ComponentActivity() {
    private var externalInput by mutableStateOf<ExternalInput?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdConsentManager.requestConsent(this)
        externalInput = parseExternalInput(intent)
        val container = (application as EqrApplication).container

        setContent {
            val settings = container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
                .value

            EqrTheme(
                themeMode = settings.themeMode,
                amoledEnabled = settings.amoledEnabled,
            ) {
                EqrRoot(
                    container = container,
                    settings = settings,
                    externalInput = externalInput,
                    onExternalInputConsumed = { externalInput = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalInput = parseExternalInput(intent)
    }
}
