package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.EntitlerIdentityClient
import com.entitlerhq.entitler.Scope
import com.entitlerhq.entitler.ServerCustomer
import kotlin.time.Duration.Companion.minutes

/** In-app client: minting a token on the server. */
suspend fun mintToken(customer: ServerCustomer): String {
    val issued = customer.token(scopes = listOf(Scope.EntitlementsRead, Scope.UsageRead, Scope.UsageWrite), ttl = 15.minutes)
    return issued.token
}

/** In-app client: a token provider. */
fun tokenProvider(api: YourApi): EntitlerClient = EntitlerClient({ api.fetchEntitlerToken() })

/** In-app client: an identity token. */
suspend fun identityClient(api: YourApi) {
    val entitler = EntitlerIdentityClient("ent_live_publishable_key", { api.identityToken() })
    if (entitler.scopes().registration == true) {
        entitler.register()
    }
}

/** In-app client: keeping the visitor across launches. */
fun keptVisitor(
    token: String,
    stored: String?,
    store: (String) -> Unit,
): EntitlerClient {
    val entitler = EntitlerClient(token) { visitor = stored }
    store(entitler.visitor)
    return entitler
}
