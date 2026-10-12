package com.entitlerhq.examples.testing

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.UsageOutcome

/** An app's own gating and billing code, tested with fakeCustomer. */
class ExportService(
    private val customer: Customer,
) {
    /** Exports a report when the customer has the feature and credits remain, charging one credit for it. */
    suspend fun export(reportId: String): String {
        if (!customer.isEntitled(Feature.OnOff("export_pdf"))) return "Upgrade to export PDFs."
        val charge = customer.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "export-$reportId")
        return if (charge.outcome == UsageOutcome.Refused) "Out of credits." else "Exported $reportId."
    }

    /** Starts an upgrade, answering the page to open, or null when the change is already made. */
    suspend fun upgrade(returnUrl: String): String? =
        when (val step = customer.subscribe("pro", period = "monthly", returnUrl = returnUrl)) {
            is SubscribeStep.Pay -> step.url
            else -> null
        }
}
