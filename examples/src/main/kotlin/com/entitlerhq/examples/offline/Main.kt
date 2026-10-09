package com.entitlerhq.examples.offline

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.verifySnapshot
import kotlinx.coroutines.runBlocking

/** Fetches a snapshot and the keys, then verifies the snapshot offline and checks a feature. Run it with ENTITLER_KEY set. */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("offline-customer")
            customer.register(name = "Hedy Lamarr")
            val environment = customer.check(Feature.OnOff("export_pdf")).environment.id

            val keys = entitler.snapshotKeys()
            val snapshot = customer.snapshot()

            val verified = verifySnapshot(snapshot.token, keys, customer = customer.id, environment = environment)
            println("Verified offline until ${verified.expiresAt}.")
            println("Export to PDF: ${verified.entitlements.has(Feature.OnOff("export_pdf"))}")
        }
    }
