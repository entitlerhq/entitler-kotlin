package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerServer
import okhttp3.OkHttpClient
import java.time.Duration

/** Configuration: sharing an OkHttp client. */
fun sharedHttpClient(http: OkHttpClient): EntitlerServer =
    EntitlerServer(System.getenv("ENTITLER_KEY")) {
        baseUrl = "https://api.entitler.dev"
        httpClient = http.newBuilder().callTimeout(Duration.ofSeconds(30)).build()
    }

/** Configuration: the server client, created on first use. */
object Entitler {
    /** The process's one server client, created when first used, so a build that loads this class without the key still works. */
    val server: EntitlerServer by lazy { EntitlerServer(System.getenv("ENTITLER_KEY")) }
}
