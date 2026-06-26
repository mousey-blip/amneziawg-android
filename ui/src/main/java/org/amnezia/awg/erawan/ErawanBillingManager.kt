/*
 * Google Play Billing wrapper for Erawan VPN.
 * Soft paywall: free tier always works; this only upgrades to "paid" tier.
 * Product: premium_monthly (recurring subscription, $4.99/mo)
 * After server-side verify succeeds, purchase is acknowledged within the
 * 3-day window to prevent automatic refund.
 */
package org.amnezia.awg.erawan

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ErawanBillingManager(
    private val activity: FragmentActivity,
    private val prefs: ErawanPrefs,
    private val onTierUpdated: (String) -> Unit,
    private val onError: (msgRes: Int) -> Unit,
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "ErawanBilling"
        const val PRODUCT_ID = "premium_monthly"
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .setListener(this)
        .build()

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    activity.lifecycleScope.launch { handlePurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> { /* user dismissed */ }
            else -> {
                Log.e(TAG, "onPurchasesUpdated error ${result.responseCode}: ${result.debugMessage}")
                onError(org.amnezia.awg.R.string.erawan_billing_error)
            }
        }
    }

    /** Connect billing client, then query product and launch the Play purchase UI. */
    fun queryAndLaunch() {
        if (billingClient.isReady) {
            doQueryAndLaunch()
        } else {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        doQueryAndLaunch()
                    } else {
                        Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                        onError(org.amnezia.awg.R.string.erawan_billing_unavailable)
                    }
                }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    /** Re-check any pending purchases (e.g. on app resume after a pending state). */
    fun checkExistingPurchases() {
        if (!billingClient.isReady) return
        activity.lifecycleScope.launch {
            val result = billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
            result.purchasesList.forEach { handlePurchase(it) }
        }
    }

    private fun doQueryAndLaunch() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        activity.lifecycleScope.launch {
            val result = billingClient.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK ||
                result.productDetailsList.isNullOrEmpty()
            ) {
                Log.e(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
                onError(org.amnezia.awg.R.string.erawan_billing_unavailable)
                return@launch
            }
            val productDetails = result.productDetailsList!![0]
            val offerToken = productDetails.subscriptionOfferDetails
                ?.firstOrNull()?.offerToken
                ?: run {
                    Log.e(TAG, "No offer token found")
                    onError(org.amnezia.awg.R.string.erawan_billing_unavailable)
                    return@launch
                }

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()

            // launchBillingFlow must be called on the main thread
            withContext(Dispatchers.Main) {
                billingClient.launchBillingFlow(activity, flowParams)
            }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val appToken = prefs.appToken ?: return

        try {
            val verifyResult = ErawanApi.verifyPurchase(
                appToken = appToken,
                purchaseToken = purchase.purchaseToken,
                productId = PRODUCT_ID,
                orderId = purchase.orderId
            )
            prefs.tier = verifyResult.tier
            prefs.premiumExpiresAt = verifyResult.expiresAt

            // Acknowledge within 3 days to prevent automatic refund
            if (!purchase.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                val ackResult = billingClient.acknowledgePurchase(ackParams)
                if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.e(TAG, "Acknowledge failed: ${ackResult.debugMessage}")
                }
            }

            withContext(Dispatchers.Main) { onTierUpdated(verifyResult.tier) }
        } catch (e: Throwable) {
            Log.e(TAG, "handlePurchase verify failed", e)
            withContext(Dispatchers.Main) { onError(org.amnezia.awg.R.string.erawan_billing_error) }
        }
    }

    fun disconnect() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
