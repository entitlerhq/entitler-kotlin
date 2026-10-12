package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.EntitlerException
import com.entitlerhq.entitler.EntitlerSnapshotException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.SnapshotExpectation
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
        val verified = verifySnapshot(token, SnapshotExpectation(bundledKeys, customer = userId, environment = environmentId))
        verified.entitlements.has(Features.exportPdf)
    } catch (error: EntitlerSnapshotException) {
        if (error.code == ErrorCode.SnapshotExpired) println("Connect to refresh your plan.")
        false
    }

/** Stands in for the app's own trusted storage of snapshot keys, apart from the snapshot. */
interface TrustedKeyStore {
    /** The keys the app holds: the ones it shipped, or the last fetched from Entitler. */
    fun read(): SnapshotKeys

    /** Keeps keys fetched from Entitler. */
    fun write(keys: SnapshotKeys)
}

/** Offline snapshots: refreshing the keys whenever the app is online. */
suspend fun refreshKeys(
    entitler: EntitlerClient,
    trusted: TrustedKeyStore,
) {
    try {
        trusted.write(entitler.snapshotKeys())
    } catch (error: EntitlerException) {
        if (!error.isUnreachable) throw error
    }
}

/** Offline snapshots: checking online, and falling back to the snapshot when Entitler is unreachable. */
suspend fun canExport(
    me: Customer,
    storedSnapshot: String,
    expected: SnapshotExpectation,
): Boolean =
    try {
        me.check(Features.exportPdf).entitled
    } catch (error: EntitlerException) {
        if (!error.isUnreachable) throw error
        verifySnapshot(storedSnapshot, expected).entitlements.has(Features.exportPdf)
    }
