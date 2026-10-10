package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.ServerCustomer
import com.entitlerhq.examples.generatedfeatures.Features

/** Tracks: moving a customer to a beta track and back. */
suspend fun betaTrack(customer: ServerCustomer) {
    customer.setTrack("Beta")
    println(customer.check(Features.exportPdf).track.name)
    customer.setTrack(null)
}
