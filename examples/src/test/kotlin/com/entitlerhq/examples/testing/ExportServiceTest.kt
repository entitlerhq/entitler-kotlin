package com.entitlerhq.examples.testing

import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.testing.fakeCustomer
import com.entitlerhq.entitler.testing.metered
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExportServiceTest {
    @Test
    fun `exports and charges a credit, then runs out`() =
        runBlocking<Unit> {
            val customer = fakeCustomer("export_pdf" to true, "ai_credits" to metered(value = 100, used = 99))
            val service = ExportService(customer)
            assertEquals("Exported r1.", service.export("r1"))
            assertEquals("Out of credits.", service.export("r2"))
            assertEquals(listOf("recordUsage", "recordUsage"), customer.writes.map { it.method })
            assertEquals("export-r1", customer.writes.first().arguments["idempotencyKey"])
        }

    @Test
    fun `asks for an upgrade without the feature`() =
        runBlocking<Unit> {
            val service = ExportService(fakeCustomer("export_pdf" to false))
            assertEquals("Upgrade to export PDFs.", service.export("r1"))
        }

    @Test
    fun `an upgrade opens the payment page, or needs nothing when done`() =
        runBlocking<Unit> {
            val customer = fakeCustomer()
            assertNull(ExportService(customer).upgrade("https://example.com/back"))
            customer.answer("subscribe") { SubscribeStep.Pay("https://checkout.example.com/pay") }
            assertEquals("https://checkout.example.com/pay", ExportService(customer).upgrade("https://example.com/back"))
        }
}
