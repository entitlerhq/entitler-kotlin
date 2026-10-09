package com.entitlerhq.examples.ktor

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** The application: one shared client, closed when the application stops. */
fun Application.module(entitler: EntitlerServer) {
    monitor.subscribe(ApplicationStopped) { entitler.close() }
    routing {
        route("/reports/pdf") {
            install(RequireFeature) {
                this.entitler = entitler
                feature = Feature.OnOff("export_pdf")
            }
            get { call.respondText("Your PDF is ready.") }
        }
    }
}

/** Serves the application on port 8080. Run it with ENTITLER_KEY set. */
fun main() {
    embeddedServer(Netty, port = 8080) { module(EntitlerServer(System.getenv("ENTITLER_KEY"))) }.start(wait = true)
}
