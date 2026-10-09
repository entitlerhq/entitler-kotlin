package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.examples.generatedfeatures.Features

/** The README's quick start. */
suspend fun quickStart() {
    val entitler = EntitlerServer(System.getenv("ENTITLER_KEY"))

    val customer = entitler.customer("user_123")
    customer.register(name = "Ada Lovelace", email = "ada@example.com")

    if (customer.isEntitled(Features.exportPdf)) {
        exportPdf()
    }

    customer.recordUsage(Features.aiCredits, 5)
}

/** The README's in-app client. */
suspend fun inAppQuickStart(api: YourApi) {
    val entitler = EntitlerClient({ api.fetchEntitlerToken() })

    if (entitler.me.isEntitled(Features.exportPdf)) {
        showExportButton()
    }
}
