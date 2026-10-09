package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerSnapshotException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.SnapshotKeys
import com.entitlerhq.entitler.verifySnapshot
import com.entitlerhq.examples.generatedfeatures.Features
import kotlin.time.Duration.Companion.days

/** Offline snapshots: minting one while online. */
suspend fun mintSnapshot(customer: Customer): String = customer.snapshot(ttl = 7.days).token

/** Offline snapshots: verifying offline with keys shipped in the app. */
fun verifyOffline(
    token: String,
    bundledKeys: SnapshotKeys,
    userId: String,
    environmentId: String,
): Boolean =
    try {
        val verified = verifySnapshot(token, bundledKeys, customer = userId, environment = environmentId)
        verified.entitlements.has(Features.exportPdf)
    } catch (error: EntitlerSnapshotException) {
        if (error.code == ErrorCode.SnapshotExpired) println("Connect to refresh your plan.")
        false
    }
