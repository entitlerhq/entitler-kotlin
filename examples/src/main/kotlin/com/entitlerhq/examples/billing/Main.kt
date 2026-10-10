package com.entitlerhq.examples.billing

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.MoveAction
import com.entitlerhq.entitler.PlanBilling
import com.entitlerhq.entitler.SubscribeStep
import kotlinx.coroutines.runBlocking
import java.util.UUID

private const val RETURN_URL = "https://example.com/billing/return"

/**
 * A billing page from plans(), subscribe handling every next step, the return page, cancel and undo, the
 * billing portal, and the company's side: a plan billed outside any provider and a grant whose id it keeps.
 * Run it with ENTITLER_KEY set.
 */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("billing-customer")
            customer.register(name = "Mary Jackson")

            billingPage(customer)
            choose(customer, "pro", "monthly")
            returnPage(customer)

            println("Cancel: ${customer.cancel().effective}; undo: ${customer.undoPendingChange().changed}.")
            try {
                println("Billing portal: ${customer.billingPortal(RETURN_URL).url}")
            } catch (error: EntitlerApiException) {
                if (error.code != ErrorCode.Stale) throw error
                println("No provider has billed this customer yet, so there is no billing portal.")
            }

            val deal = "deal-" + UUID.randomUUID()
            val contract = customer.setPlan("pro", period = "yearly", billing = PlanBilling.End, actor = "sales", idempotencyKey = deal)
            println("Invoiced contract: ${contract.plan?.name}, changed ${contract.changed}.")
            val grant = customer.grant(Feature.OnOff("sso"), days = 14, actor = "support", reason = "Pilot").grant
            println("Granted single sign-on for 14 days as ${grant.id}.")
            customer.revokeGrant(grant.id, actor = "support")
            customer.setPlan("free", actor = "sales")
        }
    }

private suspend fun billingPage(customer: Customer) {
    val plans = customer.plans(revalidate = true)
    for (held in plans.held) {
        val renews = held.renewsAt?.let { ", renews $it" }.orEmpty()
        println("Holds ${held.plan.name} (${held.period?.label ?: "no period"}$renews), billed by ${held.billedBy ?: "nobody"}.")
    }
    for (option in plans.options) {
        val button =
            when (option.action) {
                MoveAction.Buy -> "Choose ${option.plan.name}"
                MoveAction.Contact -> "Contact sales about ${option.plan.name}"
                else -> "${option.plan.name}: ${option.reason}"
            }
        println("$button (${option.move}, ${option.periods.joinToString { it.label }})")
    }
}

private suspend fun choose(
    customer: Customer,
    plan: String,
    period: String,
) {
    when (val step = customer.subscribe(plan, period = period, returnUrl = RETURN_URL)) {
        is SubscribeStep.Done -> println("Now on ${step.change.plan?.name}, effective ${step.change.effective}.")
        is SubscribeStep.Pay -> println("Open ${step.url} for the customer to pay.")
        SubscribeStep.Confirming -> println("The bank is confirming the payment; show the plan as pending.")
        is SubscribeStep.Manage -> println("${step.billedBy} bills this plan: send the customer to its subscriptions page.")
        is SubscribeStep.Unknown -> println("Show the plan as pending (${step.value}).")
    }
}

private suspend fun returnPage(customer: Customer) {
    val sync = customer.syncBilling()
    val plans = customer.plans(revalidate = true)
    println("Back from the provider: changed ${sync.changed}, holding ${plans.held.joinToString { it.plan.name }}.")
}
