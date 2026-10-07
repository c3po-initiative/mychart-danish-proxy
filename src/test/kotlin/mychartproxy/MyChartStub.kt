package mychartproxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue

/** Synthetic Sundhedsplatformen (MyChart) upstream serving `mychart-stub/fixtures.json`. */
class MyChartStub(val scenario: Scenario = Scenario.HAPPY) : AutoCloseable {
    enum class Scenario { HAPPY, LOGIN_PAGE }

    data class Request(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String)

    private val mapper = ObjectMapper()
    private val recent: Instant = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    private val fixtures: JsonNode = requireNotNull(javaClass.getResourceAsStream("/mychart-stub/fixtures.json"))
        .use { it.readBytes().toString(UTF_8) }
        .replace("\"RECENT_T\"", "\"$recent\"")
        .replace("\"RECENT\"", "\"${recent.atZone(ZoneId.of("Europe/Copenhagen")).toLocalDate()}\"")
        .let(mapper::readTree)
    private val history = ConcurrentLinkedQueue<Request>()
    val requests: List<Request> get() = history.toList()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.use {
                val body = ex.requestBody.readBytes().toString(UTF_8)
                val path = ex.requestURI.path.removePrefix(INSTANCE)
                history += Request(ex.requestMethod, path, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }, body)
                val (status, contentType, payload) = respond(path, body)
                val bytes = payload.toByteArray(UTF_8)
                ex.responseHeaders.add("Content-Type", contentType)
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
            }
        }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun respond(path: String, body: String): Triple<Int, String, String> {
        if (scenario == Scenario.LOGIN_PAGE) {
            return Triple(200, "text/html; charset=utf-8", "<!DOCTYPE html><html><body>Log ind</body></html>")
        }
        val node = fixtures.get(path) ?: return Triple(404, "text/plain", "not found")
        if (path == "/api/referrals/getReferralDetails") {
            val requested = mapper.readTree(body).path("RflId").asText()
            if (requested != node.path("internalId").asText()) return Triple(404, "text/plain", "not found")
        }
        return Triple(200, "application/json; charset=utf-8", mapper.writeValueAsString(node))
    }

    override fun close() = server.stop(0)

    companion object {
        const val INSTANCE = "/MyChartPPR1"
    }
}
