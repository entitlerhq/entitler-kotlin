package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.examples.generatedfeatures.Features

/** Checking access: the check answer. */
suspend fun checkAnswer(customer: Customer) {
    val check = customer.check(Features.exportPdf)
    if (check.entitled) {
        exportPdf()
    } else {
        println("Upgrade to ${check.upgrades.firstOrNull()?.name}")
    }
}

/** Checking access: a metered check. */
suspend fun meteredCheck(customer: Customer) {
    val credits = customer.check(Features.aiCredits)
    println("${credits.used} used, ${credits.held} on hold, ${credits.remaining} left, starting again at ${credits.resetsAt}")
}

/** Checking access: a setting's value. */
suspend fun settingValue(customer: Customer) {
    val seats =
        when (val value = customer.check(Features.teamSeats).value) {
            FeatureValue.On -> "Included"
            is FeatureValue.Amount -> "${value.value} seats"
            FeatureValue.Unlimited -> "Unlimited seats"
        }
    println(seats)
}

/** Checking access: failing closed and open. */
suspend fun failClosedOrOpen(customer: Customer) {
    val canExport = customer.isEntitled(Features.exportPdf)
    val canSeeTips = customer.isEntitled(Feature.OnOff("tips"), default = true)
    println("$canExport $canSeeTips")
}

/** Checking access: every entitlement at once. */
suspend fun everyEntitlement(customer: Customer) {
    val entitlements = customer.entitlements()
    if (entitlements.has(Features.collaboration)) {
        println("Team tools are on.")
    }
    val seats = entitlements[Features.teamSeats]?.value
    println(seats)
}

/** Checking access: features declared by hand. */
object MyFeatures {
    /** Download any document as a PDF. */
    val exportPdf = Feature.OnOff("export_pdf")

    /** Spent on AI actions each month. */
    val aiCredits = Feature.Metered("ai_credits")
}

/** Checking access: asking again after a change the customer just made. */
suspend fun afterUpgrade(customer: Customer) {
    val check = customer.check(Features.exportPdf, revalidate = true)
    if (check.entitled) showExportButton()
}
