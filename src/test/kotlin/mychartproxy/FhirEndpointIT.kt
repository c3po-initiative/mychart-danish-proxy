package mychartproxy

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Full Spring Boot application against the MyChart stub: servlet wiring, search params, HTTP statuses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FhirEndpointIT {
    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun get(path: String, session: Boolean = true): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/fhir/$path")).header("Accept", "application/fhir+json")
        if (session) builder.header("Cookie", "MyChartPPR1=session-abc").header("__RequestVerificationToken", "csrf-123")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `every supported resource type answers 200 with a searchset bundle`() {
        listOf(
            "Patient", "Observation", "Observation?category=vital-signs", "DiagnosticReport", "ImagingStudy",
            "Condition", "Encounter", "Appointment", "DocumentReference", "ServiceRequest", "Organization"
        ).forEach { q ->
            val response = get(q)
            assertEquals(200, response.statusCode(), "$q: ${response.body().take(300)}")
            assertTrue(response.body().contains("\"searchset\""), q)
        }
    }

    @Test
    fun `capability statement lists the supported resources`() {
        val body = get("metadata", session = false).body()
        listOf("Patient", "Observation", "Condition", "Encounter", "Appointment", "DocumentReference", "ServiceRequest", "Organization")
            .forEach { assertTrue(body.contains("\"type\": \"$it\"") || body.contains("\"type\":\"$it\""), it) }
    }

    @Test
    fun `upstream session rejections map to FHIR 401, 403 and 502`() {
        fun status(code: Int) = runCatching {
            mychartproxy.controller.MyChartRequest.resources<org.hl7.fhir.r4.model.Patient> {
                throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(code), "x")
            }
        }.exceptionOrNull().let { (it as ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException).statusCode }
        assertEquals(401, status(401))
        assertEquals(403, status(403))
        assertEquals(502, status(500))
    }

    companion object {
        private val stub = MyChartStub()

        @JvmStatic
        @DynamicPropertySource
        fun upstream(registry: DynamicPropertyRegistry) {
            registry.add("mychart.client.base-url") { stub.baseUrl }
            registry.add("mychart.client.instance-path") { MyChartStub.INSTANCE }
        }

        @JvmStatic
        @AfterAll
        fun stop() = stub.close()
    }
}
