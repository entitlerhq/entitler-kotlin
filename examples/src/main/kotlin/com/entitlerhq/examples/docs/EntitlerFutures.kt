package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.Feature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

/** Calls the SDK from Java through futures. */
object EntitlerFutures {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether [customer] is entitled to [feature], as a future. */
    @JvmStatic
    fun isEntitled(
        customer: Customer,
        feature: Feature,
    ): CompletableFuture<Boolean> = scope.future { customer.isEntitled(feature) }
}
