package com.entitlerhq.examples.offline

import com.entitlerhq.entitler.EntitlerException
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.SnapshotExpectation
import com.entitlerhq.entitler.verifySnapshot
import kotlinx.coroutines.runBlocking

private val exportPdf = Feature.OnOff("export_pdf")

/**
 * Fetches a snapshot and the keys, then checks a feature online and falls back to the snapshot, verified
 * offline, when Entitler cannot be reached. Run it with ENTITLER_KEY set.
 */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("offline-customer")
            customer.register(name = "Hedy Lamarr")
            val environment = customer.check(exportPdf).environment.id

            val keys = entitler.snapshotKeys()
            val stored = customer.snapshot().token
            val expected = SnapshotExpectation(keys, customer = customer.id, environment = environment)

            val canExport =
                try {
                    customer.check(exportPdf).entitled
                } catch (error: EntitlerException) {
                    if (!error.isUnreachable) throw error
                    verifySnapshot(stored, expected).entitlements.has(exportPdf)
                }
            println("Export to PDF: $canExport")

            val verified = verifySnapshot(stored, expected)
            println("The snapshot verifies offline until ${verified.expiresAt}.")
        }
    }
