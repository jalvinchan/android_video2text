package com.example.video2text

import android.app.Activity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AdsState(val canRequest: Boolean = false, val privacyOptionsRequired: Boolean = false)

/** Consent gates SDK initialization and ad requests. No media/text is passed to the ad SDK. */
internal class FreePlanAds {
    private val mutable = MutableStateFlow(AdsState())
    val state = mutable.asStateFlow()
    private var updating = false
    private var initialized = false
    private var initializing = false

    fun updateConsent(activity: Activity) {
        if (!BuildConfig.MONETIZATION_ENABLED || updating) return
        updating = true
        val information = UserMessagingPlatform.getConsentInformation(activity)
        information.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                updating = false
                publish(activity)
            }
        }, {
            updating = false
            publish(activity) // Can use valid consent from a previous session; otherwise no ads.
        })
    }

    fun privacyOptions(activity: Activity) {
        mutable.value = mutable.value.copy(canRequest = false)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { publish(activity) }
    }

    private fun publish(activity: Activity) {
        val information = UserMessagingPlatform.getConsentInformation(activity)
        val privacy = information.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (!information.canRequestAds()) { mutable.value = AdsState(privacyOptionsRequired = privacy); return }
        if (initialized) mutable.value = AdsState(canRequest = true, privacyOptionsRequired = privacy)
        else if (!initializing) {
            initializing = true
            MobileAds.initialize(activity.applicationContext) {
                initialized = true
                initializing = false
                publish(activity) // Re-read choices in case they changed during initialization.
            }
        }
    }
}

@Composable
internal fun FreePlanBanner() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthDp = with(density) { constraints.maxWidth.toDp().value.toInt() }.coerceAtLeast(1)
        val view = remember(context, widthDp) {
            AdView(context).apply {
                adUnitId = BuildConfig.BANNER_AD_UNIT_ID
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
                loadAd(AdRequest.Builder().build())
            }
        }
        DisposableEffect(view, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) { Lifecycle.Event.ON_RESUME -> view.resume(); Lifecycle.Event.ON_PAUSE -> view.pause(); else -> Unit }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); view.destroy() }
        }
        key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth()) }
    }
}
