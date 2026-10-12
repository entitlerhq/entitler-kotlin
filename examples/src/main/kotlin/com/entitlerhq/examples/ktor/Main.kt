package com.entitlerhq.examples.ktor

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * The application: one shared client, closed when the application stops. [userIdFor] turns a session
 * token your sign-in issued into the customer id, or null when the session is unknown.
 */
fun Application.module(
    entitler: EntitlerServer,
    userIdFor: suspend (String) -> String?,
) {
    monitor.subscribe(ApplicationStopped) { entitler.close() }
    install(Authentication) {
        bearer { authenticate { credential -> userIdFor(credential.token)?.let(::UserIdPrincipal) } }
    }
    routing {
        authenticate {
            route("/reports/pdf") {
                install(RequireFeature) {
                    this.entitler = entitler
                    feature = Feature.OnOff("export_pdf")
                }
                get { call.respondText("Your PDF is ready.") }
            }
        }
    }
}

/** A stand-in for your session store: the sessions your sign-in issued, by token. */
private val sessions = mapOf("demo-session" to "user_123")

/** Serves the application on port 8080. Run it with ENTITLER_KEY set. */
fun main() {
    embeddedServer(Netty, port = 8080) { module(EntitlerServer(System.getenv("ENTITLER_KEY"))) { sessions[it] } }.start(wait = true)
}
