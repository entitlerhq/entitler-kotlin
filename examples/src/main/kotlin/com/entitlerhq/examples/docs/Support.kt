package com.entitlerhq.examples.docs

/** Stands in for your app's own API in the documentation snippets. */
interface YourApi {
    /** Asks your server for a customer token it minted with `ServerCustomer.token()`. */
    suspend fun fetchEntitlerToken(): String

    /** Asks your sign-in provider for the person's identity token. */
    suspend fun identityToken(): String
}

/** Stands in for your app's work in the documentation snippets. */
fun exportPdf() = Unit

/** Stands in for your app's interface in the documentation snippets. */
fun showExportButton() = Unit

/** Stands in for your app's interface in the documentation snippets. */
fun showUpgradePrompt() = Unit

/** Stands in for a model call whose cost is known only afterwards. */
suspend fun summarise(text: String): Summary = Summary(text.take(100), creditsUsed = 3)

/** A model's answer and what it cost. */
data class Summary(
    /** The summary. */
    val text: String,
    /** The credits it used. */
    val creditsUsed: Long,
)
