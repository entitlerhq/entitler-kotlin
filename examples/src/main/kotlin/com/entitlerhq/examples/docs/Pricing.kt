package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.ServerCustomer

/** Pricing pages: signed-out pricing with a visitor. */
suspend fun signedOutPricing(
    entitler: EntitlerServer,
    cookieVisitor: String?,
): String {
    val visitor = cookieVisitor ?: entitler.newVisitorId()
    val pricing = entitler.pricing(visitor = visitor)
    pricing.plans.forEach { plan -> println("${plan.name}: ${plan.periods.joinToString { it.label }}") }
    return visitor
}

/** Pricing pages: keeping the arm after sign-up. */
suspend fun signUp(
    customer: ServerCustomer,
    visitor: String,
) {
    customer.register(name = "Ada Lovelace", email = "ada@example.com", visitor = visitor)
    val pricing = customer.pricing(visitor = visitor)
    println(pricing.experiment?.arm)
}
