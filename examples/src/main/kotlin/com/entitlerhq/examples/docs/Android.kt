package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.newVisitorId

/** Stands in for the app's DataStore of the visitor id in the documentation snippets. */
interface VisitorStore {
    /** The kept visitor id, or null when none is kept. */
    suspend fun read(): String?

    /** Keeps the visitor id. */
    suspend fun write(visitor: String)
}

/** Stands in for opening a page in a Custom Tab, `CustomTabsIntent.Builder().build().launchUrl(context, uri)`. */
fun interface CustomTabs {
    /** Opens the page. */
    fun open(url: String)
}

/** Android: the client for the signed-in person, with the visitor from DataStore. */
fun signedInClient(
    api: YourApi,
    visitors: VisitorStore,
): EntitlerClient =
    EntitlerClient({ api.fetchEntitlerToken() }) {
        visitorSource = { visitors.read() ?: newVisitorId().also { visitors.write(it) } }
    }

/** Android: a new visitor at sign-out and at account deletion. */
suspend fun rotateVisitor(visitors: VisitorStore) {
    visitors.write(newVisitorId())
}

/** Android: an upgrade paid through Stripe in a Custom Tab, returning to an app link. */
class UpgradeViewModel(
    private val me: Customer,
    private val tabs: CustomTabs,
) {
    /** Starts the upgrade, opening the payment page when there is one. */
    suspend fun upgrade(plan: String) {
        when (val step = me.subscribe(plan, period = "monthly", returnUrl = "https://example.com/app/billing-return")) {
            is SubscribeStep.Pay -> tabs.open(step.url)
            is SubscribeStep.Manage -> tabs.open("https://play.google.com/store/account/subscriptions")
            else -> refresh()
        }
    }

    /** Called when the app link brings the person back from the payment page. */
    suspend fun onBillingReturn() {
        me.syncBilling()
        refresh()
    }

    private suspend fun refresh() {
        val plans = me.plans(revalidate = true)
        println("On ${plans.held.joinToString { it.plan.name }}")
    }
}
