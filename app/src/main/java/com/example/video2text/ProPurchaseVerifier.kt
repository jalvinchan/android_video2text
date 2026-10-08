package com.example.video2text

import android.util.Base64
import com.android.billingclient.api.Purchase
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.json.JSONObject

/** Google signs the original payload. Never trust a local "isPro" boolean or a pending purchase. */
internal class ProPurchaseVerifier(private val publicKey: String, private val packageName: String) {
    fun verifiedPurchase(payload: String, signature: String): Purchase? = runCatching {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT)))
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(key)
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        require(verifier.verify(Base64.decode(signature, Base64.DEFAULT)))
        // The SDK maps legacy signed states to its public enum; only state 0 is completed.
        require(JSONObject(payload).getInt("purchaseState") == 0)
        Purchase(payload, signature).also {
            require(it.packageName == packageName && PRO_PRODUCT in it.products &&
                it.purchaseState == Purchase.PurchaseState.PURCHASED && it.purchaseToken.isNotBlank())
        }
    }.getOrNull()

    companion object { const val PRO_PRODUCT = "pro_unlock" }
}
