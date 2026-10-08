package com.example.video2text

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPairGenerator
import java.security.Signature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProPurchaseVerifierTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = ProPurchaseVerifier(Base64.encodeToString(keys.public.encoded, Base64.NO_WRAP), "example.app")
    private fun payload(product: String = "pro_unlock", packageName: String = "example.app", state: Int = 0) =
        JSONObject().put("productId", product).put("packageName", packageName).put("purchaseState", state)
            .put("purchaseToken", "verified-token").put("acknowledged", true).toString()
    private fun sign(payload: String): String = Signature.getInstance("SHA1withRSA").run {
        initSign(keys.private); update(payload.toByteArray(Charsets.UTF_8)); Base64.encodeToString(sign(), Base64.NO_WRAP)
    }

    @Test fun signedProPurchaseCanBeRestoredAndTamperingIsRejected() {
        val data = payload()
        val signature = sign(data)
        assertNotNull(verifier.verifiedPurchase(data, signature))
        assertNull(verifier.verifiedPurchase(data.replace("verified-token", "changed-token"), signature))
        assertNull(verifier.verifiedPurchase(data, "broken"))
        assertNull(ProPurchaseVerifier("invalid-key", "example.app").verifiedPurchase(data, signature))
    }

    @Test fun pendingPurchaseOtherProductAndOtherAppDoNotUnlockPro() {
        for (data in listOf(payload(state = 4), payload(state = 1), payload(product = "credits"), payload(packageName = "another.app"))) {
            assertNull(verifier.verifiedPurchase(data, sign(data)))
        }
    }
}
