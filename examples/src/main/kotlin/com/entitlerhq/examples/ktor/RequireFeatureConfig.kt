package com.entitlerhq.examples.ktor

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

/** What [RequireFeature] needs: the shared server client and the feature a route requires. */
class RequireFeatureConfig {
    /** The server client, shared by the whole application. */
    lateinit var entitler: EntitlerServer

    /** The feature the route requires. */
    lateinit var feature: Feature
}

/**
 * A route plugin that answers 402 unless the signed-in customer is entitled to a feature. It reads the
 * customer id from the principal your authentication verified, never from a header the caller can set.
 */
val RequireFeature =
    createRouteScopedPlugin("RequireFeature", ::RequireFeatureConfig) {
        val entitler = pluginConfig.entitler
        val feature = pluginConfig.feature
        onCall { call ->
            val userId = call.principal<UserIdPrincipal>()?.name
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
            } else if (!entitler.customer(userId).isEntitled(feature)) {
                call.respond(HttpStatusCode.PaymentRequired, "Upgrade to use ${feature.key}.")
            }
        }
    }
