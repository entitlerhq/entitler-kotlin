package com.entitlerhq.examples.testing

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.testing.Answers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExportServiceHttpTest {
    @Test
    fun `asks for an upgrade when Entitler answers not entitled`() =
        runBlocking<Unit> {
            MockWebServer().use { web ->
                web.start()
                web.enqueue(MockResponse.Builder().body(Answers.body(Answers.check(entitled = false))).build())
                val entitler =
                    EntitlerServer("ent_test_key") {
                        baseUrl = web.url("/").toString()
                        maxRetries = 0
                        cache = null
                    }
                entitler.use {
                    val service = ExportService(it.customer("user_1"))
                    assertEquals("Upgrade to export PDFs.", service.export("r1"))
                }
            }
        }
}
