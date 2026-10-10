package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.entitler.MoveAction
import com.entitlerhq.entitler.PlanBilling
import com.entitlerhq.entitler.Provider
import com.entitlerhq.entitler.ServerCustomer
import com.entitlerhq.entitler.Sku
import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.Timing
import com.entitlerhq.examples.generatedfeatures.Features
import java.time.Instant

/** Billing pages: the buttons from plans(). */
suspend fun planButtons(customer: Customer) {
    val plans = customer.plans()
    for (held in plans.held) {
        println("Your plan: ${held.plan.name}, billed by ${held.billedBy ?: "your account manager"}")
    }
    for (option in plans.options) {
        when (option.action) {
            MoveAction.Buy -> println("Choose ${option.plan.name} (${option.periods.joinToString { it.label }})")
            MoveAction.Contact -> println("Talk to us about ${option.plan.name}")
            else -> println("${option.plan.name} is unavailable: ${option.reason}")
        }
    }
}

/** Billing pages: subscribe and each next step. */
suspend fun choosePlan(
    customer: Customer,
    plan: String,
    period: String,
): String? =
    when (val step = customer.subscribe(plan, period = period, returnUrl = "https://example.com/billing/return")) {
        is SubscribeStep.Done -> null
        is SubscribeStep.Pay -> step.url
        SubscribeStep.Confirming -> null.also { println("Your payment is being confirmed.") }
        is SubscribeStep.Manage -> null.also { println("Change your plan in your ${step.billedBy} subscriptions.") }
        is SubscribeStep.Unknown -> null
    }

/** Billing pages: the return page. */
suspend fun billingReturn(customer: Customer) {
    customer.syncBilling()
    val plans = customer.plans(revalidate = true)
    println("You're on ${plans.held.joinToString { it.plan.name }}")
}

/** Billing pages: cancelling and undoing. */
suspend fun cancelAndUndo(customer: Customer) {
    val cancel = customer.cancel()
    println("Your plan ends ${cancel.at}")
    customer.undoPendingChange()
    customer.cancel(addOn = "extra_seats")
}

/** Billing pages: the billing portal. */
suspend fun portal(customer: Customer): String? =
    try {
        customer.billingPortal(returnUrl = "https://example.com/account").url
    } catch (error: EntitlerApiException) {
        if (error.code != ErrorCode.Stale) throw error
        null
    }

/** Company decisions: an invoiced contract, an add-on and a goodwill upgrade. */
suspend fun companyDecisions(
    customer: ServerCustomer,
    dealId: String,
) {
    customer.setPlan("enterprise", period = "yearly", billing = PlanBilling.End, actor = "hubspot", idempotencyKey = "deal-$dealId")
    customer.setAddOn("extra_seats", quantity = 150, timing = Timing.Now, actor = "hubspot", idempotencyKey = "deal-$dealId-seats")
    customer.setPlan("team", billing = PlanBilling.Keep, until = Instant.parse("2026-12-31T00:00:00Z"), reason = "Outage credit")
}

/** Company decisions: grants and meters from a support tool. */
suspend fun supportTool(
    customer: ServerCustomer,
    agentId: String,
    ticketId: String,
) {
    val change = customer.grant(Features.teamSeats, FeatureValue.Amount(10), days = 30, actor = agentId, reason = "Pilot")
    println("Grant ${change.grant.id}")
    customer.revokeGrant(change.grant.id, actor = agentId)
    customer.adjustMeter(Features.aiCredits, idempotencyKey = "ticket-$ticketId", by = -500, actor = agentId, reason = "Failed run")
}

/** A purchase your server verified with the store. */
data class VerifiedPurchase(
    /** The store's transaction id. */
    val transactionId: String,
    /** The store's product id. */
    val productId: String,
    /** When the period paid for ends. */
    val expiresAt: Instant,
)

/** Store purchases: recording a verified App Store purchase or renewal. */
suspend fun recordApplePurchase(
    customer: ServerCustomer,
    purchase: VerifiedPurchase,
) {
    customer.setPlan(
        Sku("apple", mapOf("productId" to purchase.productId)),
        until = purchase.expiresAt,
        actor = "app-store",
        idempotencyKey = "apple-${purchase.transactionId}",
    )
}

/** Store purchases: recording a verified Play purchase or renewal. */
suspend fun recordPlayPurchase(
    customer: ServerCustomer,
    purchase: VerifiedPurchase,
) {
    customer.setPlan(
        Sku("google", mapOf("productId" to purchase.productId)),
        until = purchase.expiresAt,
        actor = "google-play",
        idempotencyKey = "google-${purchase.transactionId}",
    )
}

/** Store purchases: a refund or a revoked purchase. */
suspend fun storeRefund(
    customer: ServerCustomer,
    transactionId: String,
) {
    customer.setPlan("free", actor = "app-store", reason = "Refunded", idempotencyKey = "refund-$transactionId")
}

/** Store purchases: deleting the account once the store subscription is cancelled. */
suspend fun deleteAccount(customer: ServerCustomer) {
    val billedBy = customer.plans(revalidate = true).held.mapNotNull { it.billedBy }
    if (Provider.Apple in billedBy || Provider.Google in billedBy) {
        println("Cancel your subscription in the App Store or Google Play first.")
        return
    }
    customer.erase()
}
