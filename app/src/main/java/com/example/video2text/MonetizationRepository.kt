package com.example.video2text

import android.app.Activity
import android.content.Context
import android.util.AtomicFile
import com.android.billingclient.api.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class ProState(
    val enabled: Boolean = BuildConfig.MONETIZATION_ENABLED,
    val isPro: Boolean = false,
    val checked: Boolean = false,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val price: String? = null,
    val message: String? = null,
) {
    val access get() = TranscriptionAccess.forPlan(enabled, isPro)
}

/** One app-wide billing connection; the signed purchase cache is never backed up. */
internal class MonetizationRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = AtomicFile(File(context.noBackupFilesDir, "pro-purchase.json"))
    private val verifier = ProPurchaseVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY, context.packageName)
    private val mutable = MutableStateFlow(ProState(isPro = cachedPurchase() != null))
    val state = mutable.asStateFlow()
    private var connecting = false
    private var querying = false
    private var purchasing = false
    private val client = BillingClient.newBuilder(context)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener { result, _ ->
            scope.launch {
                purchasing = false
                mutable.update { it.copy(busy = false) }
                if (result.responseCode == BillingClient.BillingResponseCode.OK ||
                    result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh()
                else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED)
                    message("Purchase could not be completed. Check Google Play and try again.")
            }
        }.build()

    fun refresh() {
        if (!state.value.enabled || state.value.busy || querying || purchasing) return
        if (!client.isReady) {
            if (connecting) return
            connecting = true
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() { connecting = false }
                override fun onBillingSetupFinished(result: BillingResult) {
                    connecting = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
                    else message("Google Play purchases are unavailable. Previously restored Pro access is kept.")
                }
            })
            return
        }
        querying = true
        mutable.update { it.copy(busy = true, message = null) }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            scope.launch {
                querying = false
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    mutable.update { it.copy(busy = false, message = "Purchases could not be restored. Check your connection and try again.") }
                    return@launch
                }
                val owned = purchases.firstOrNull { ProPurchaseVerifier.PRO_PRODUCT in it.products }
                if (owned == null || owned.purchaseState == Purchase.PurchaseState.PENDING) {
                    try { persist(null) } catch (_: Exception) {
                        mutable.update { it.copy(isPro = false, checked = false, busy = false,
                            message = "Purchase status could not be saved. Check free storage and restore purchases to retry.") }
                        return@launch
                    }
                    mutable.update { it.copy(isPro = false, checked = true, busy = false,
                        pending = owned != null, message = if (owned != null) "Payment is pending. Pro unlocks after Google Play confirms it." else null) }
                } else {
                    val verified = verifier.verifiedPurchase(owned.originalJson, owned.signature)
                    if (verified == null) {
                        mutable.update { it.copy(busy = false, checked = false, message = "This purchase could not be verified. Try restoring purchases again.") }
                    } else if (verified.isAcknowledged) grant(verified)
                    else client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(verified.purchaseToken).build()) { acknowledgement ->
                        scope.launch {
                            if (acknowledgement.responseCode == BillingClient.BillingResponseCode.OK) grant(verified)
                            else mutable.update { it.copy(busy = false, message = "Purchase confirmation is incomplete. Restore purchases to retry.") }
                        }
                    }
                }
                queryProduct { product ->
                    mutable.update { it.copy(price = product?.oneTimePurchaseOfferDetails?.formattedPrice) }
                }
            }
        }
    }

    fun buy(activity: Activity) {
        if (!state.value.enabled || !state.value.checked || state.value.isPro || state.value.busy || state.value.pending || purchasing) return
        if (!client.isReady) { refresh(); return }
        purchasing = true
        mutable.update { it.copy(busy = true, message = null) }
        // Fetch current details for each purchase rather than use stale price/offer objects.
        queryProduct { product ->
            if (product == null || activity.isFinishing || activity.isDestroyed) {
                purchasing = false
                mutable.update { it.copy(busy = false, message = "Pro is not available to purchase right now. Please try again later.") }
            } else {
                val detail = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
                product.oneTimePurchaseOfferDetails?.offerToken?.let { detail.setOfferToken(it) }
                val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(detail.build())).build())
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    purchasing = false
                    mutable.update { it.copy(busy = false, message = "Purchase could not be opened. Check Google Play and try again.") }
                }
            }
        }
    }

    private fun queryProduct(available: (ProductDetails?) -> Unit) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(ProPurchaseVerifier.PRO_PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, details ->
            scope.launch { available(if (result.responseCode == BillingClient.BillingResponseCode.OK)
                details.productDetailsList.firstOrNull { it.productId == ProPurchaseVerifier.PRO_PRODUCT } else null) }
        }
    }

    private fun grant(purchase: Purchase) {
        try {
            persist(purchase)
            mutable.update { it.copy(isPro = true, checked = true, busy = false, pending = false, message = "Pro is active. No ads or recording-length limit, with background transcription.") }
        } catch (_: Exception) {
            mutable.update { it.copy(busy = false, message = "Pro could not be saved on this phone. Check free storage and restore purchases to retry.") }
        }
    }

    private fun persist(purchase: Purchase?) {
        val json = JSONObject()
        if (purchase != null) json.put("payload", purchase.originalJson).put("signature", purchase.signature)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val output = cache.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            cache.finishWrite(output)
            check(cache.openRead().use { it.readBytes().contentEquals(bytes) }) { "Purchase status was not committed." }
        } catch (error: Exception) { cache.failWrite(output); throw error }
    }

    private fun cachedPurchase(): Purchase? = runCatching {
        val json = cache.openRead().bufferedReader().use { JSONObject(it.readText()) }
        verifier.verifiedPurchase(json.getString("payload"), json.getString("signature"))
    }.getOrNull()

    private fun message(text: String) { mutable.update { it.copy(busy = false, message = text) } }
}
