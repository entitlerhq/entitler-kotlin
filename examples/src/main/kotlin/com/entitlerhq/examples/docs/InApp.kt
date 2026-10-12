package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.EntitlerIdentityClient
import com.entitlerhq.entitler.EntitlerPublishableClient
import com.entitlerhq.entitler.Scope
import com.entitlerhq.entitler.ServerCustomer
import com.entitlerhq.entitler.newVisitorId
import kotlin.time.Duration.Companion.minutes

/** In-app client: minting a token on the server. */
suspend fun mintToken(customer: ServerCustomer): String {
    val issued = customer.token(scopes = listOf(Scope.EntitlementsRead, Scope.UsageWrite), ttl = 15.minutes)
    return issued.token
}

/** In-app client: a token provider. */
fun tokenProvider(api: YourApi): EntitlerClient = EntitlerClient({ api.fetchEntitlerToken() })

/** In-app client: an identity token. */
suspend fun identityClient(api: YourApi) {
    val entitler = EntitlerIdentityClient("ent_pk_live_your_key", { api.identityToken() })
    entitler.register()
}

/** In-app client: one client per signed-in customer, held by the app and closed at sign-out. */
class EntitlerSession(
    private val api: YourApi,
) {
    private var current: EntitlerClient? = null

    /** The signed-in customer's client, created at sign-in. */
    val entitler: EntitlerClient
        get() = checkNotNull(current) { "Nobody is signed in." }

    /** Creates the client for the person who just signed in. */
    fun signIn() {
        current?.close()
        current = EntitlerClient({ api.fetchEntitlerToken() })
    }

    /** Closes the client, so nothing of this customer outlives their session. */
    fun signOut() {
        current?.close()
        current = null
    }
}

/** Pricing pages: keeping the visitor across launches, and resetting it at sign-out. */
suspend fun keptVisitor(
    publishableKey: String,
    stored: String?,
    store: (String?) -> Unit,
): EntitlerPublishableClient {
    val entitler = EntitlerPublishableClient(publishableKey) { visitor = stored }
    store(entitler.visitor())
    return entitler
}

/** Pricing pages: a new visitor after sign-out. */
fun resetVisitor(store: (String?) -> Unit) {
    store(newVisitorId())
}
