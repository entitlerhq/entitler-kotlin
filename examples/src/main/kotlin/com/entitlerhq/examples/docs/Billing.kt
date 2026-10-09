package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.ChangeTiming
import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.entitler.ServerCustomer
import com.entitlerhq.entitler.Sku
import com.entitlerhq.examples.generatedfeatures.Features

/** Changing plans: showing the plans the customer can move to. */
suspend fun upgradeOptions(customer: ServerCustomer) {
    val plans = customer.plans()
    for (option in plans.options) {
        val label = if (option.selfServe) "Switch to ${option.plan.name}" else "Talk to us about ${option.plan.name}"
        println("$label (${option.direction}, ${option.timing}): ${option.impact.joinToString { it.text }}")
    }
}

/** Billing: self-serve changes. */
suspend fun selfServe(customer: ServerCustomer) {
    customer.subscribe("pro", period = "Yearly")
    customer.addAddOn("sso_addon")
    customer.cancel(timing = ChangeTiming.End)
    customer.undoPendingChange()
}

/** Billing: a SKU bought in an app store. */
suspend fun storePurchase(customer: ServerCustomer) {
    customer.subscribe(Sku("apple", mapOf("productId" to "pro.monthly")))
}

/** Billing: checkout and the billing portal. */
suspend fun checkout(customer: ServerCustomer): String? =
    try {
        customer.checkout("pro", successUrl = "https://example.com/thanks", cancelUrl = "https://example.com/pricing").url
    } catch (error: EntitlerApiException) {
        when (error.code) {
            ErrorCode.NotSelfServe -> null
            ErrorCode.PaymentRequired -> error.payment?.url
            else -> throw error
        }
    }

/** Billing: the billing portal. */
suspend fun portal(customer: ServerCustomer): String = customer.billingPortal(returnUrl = "https://example.com/account").url

/** Billing: vendor actions. */
suspend fun vendorActions(customer: ServerCustomer) {
    customer.vendor.subscribe("team")
    customer.vendor.override("team")
    customer.vendor.undoOverride()
    val detail = customer.vendor.grant(Features.teamSeats, FeatureValue.Amount(10), days = 30, reason = "Pilot")
    customer.vendor.revokeGrant(detail.grants.first().id)
    customer.vendor.setMeter(Features.aiCredits, 0)
}
