package mychartproxy.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mychartproxy.config.MyChartClientProperties
import mychartproxy.model.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * Read-only client for the Sundhedsplatformen MyChart patient-portal API.
 *
 * MyChart serves reads as POST requests, so dhroxy's "GET only" rule cannot be enforced on
 * the HTTP verb. Instead every upstream call goes through [READ_ONLY_ENDPOINTS]: a fixed
 * allowlist of the read endpoints observed in the captured traffic. Endpoints that change
 * state (Scheduling/ReserveAppointment, DeleteReservationFromSlot, DecisionTree/NextStep,
 * analytics/audit loggers, …) are not reachable through this class.
 */
@Component
class MyChartClient(private val props: MyChartClientProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val forwardedHeaderNames = props.forwardedHeaders.map { it.lowercase() }.toSet()
    private val fallbackHeaders = props.fallbackHeaders.mapKeys { it.key.lowercase() }
    private val base = props.baseUrl.trimEnd('/') + "/" + props.instancePath.trim('/')
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(ProxySelector.getDefault())
        .build()

    // ---- Patient ----
    fun fetchProxySwitch(headers: HttpHeaders): ProxySwitchResponse? =
        get("/ProxySwitch", headers, ProxySwitchResponse::class.java)

    // ---- Organizations ----
    fun fetchOrganizations(headers: HttpHeaders): OrganizationsResponse? =
        post("/api/conversations/GetOrganizations", emptyMap<String, Any>(), headers, OrganizationsResponse::class.java)

    // ---- Test results ----
    fun fetchTestResultList(headers: HttpHeaders, maxResults: Int): TestResultListResponse? =
        post(
            "/api/test-results/GetList",
            mapOf(
                "groupType" to props.testResultsGroupType,
                "isCurAdmFilterEnabled" to false,
                "maxResults" to maxResults,
                "searchString" to ""
            ),
            headers, TestResultListResponse::class.java
        )

    fun fetchTestResultDetails(csn: String, organizationId: String?, headers: HttpHeaders): TestResultDetailsResponse? =
        post(
            "/api/test-results/GetDetailsByCSN",
            mapOf("csn" to csn, "organizationID" to (organizationId ?: ""), "PageNonce" to ""),
            headers, TestResultDetailsResponse::class.java
        )

    fun fetchInpatientResultDetails(
        orderKey: String,
        referenceId: String,
        organizationId: String?,
        headers: HttpHeaders
    ): TestResultDetailsResponse? =
        post(
            "/api/test-results/GetInpatientDetailsByCSN",
            mapOf(
                "orderKey" to orderKey,
                "referenceID" to referenceId,
                "organizationID" to (organizationId ?: ""),
                "PageNonce" to ""
            ),
            headers, TestResultDetailsResponse::class.java
        )

    // ---- Track my health ----
    fun fetchFlowsheets(headers: HttpHeaders): FlowsheetsResponse? =
        post("/api/track-my-health/GetFlowsheets", emptyMap<String, Any>(), headers, FlowsheetsResponse::class.java)

    fun fetchFlowsheetReadings(episodeId: String, headers: HttpHeaders): FlowsheetReadingsResponse? =
        post(
            "/api/track-my-health/GetFlowsheetReadings",
            mapOf(
                "episodeId" to episodeId,
                "endInstantIso" to Instant.now().toString(),
                "numReadings" to props.flowsheetReadings
            ),
            headers, FlowsheetReadingsResponse::class.java
        )

    // ---- Health issues ----
    fun fetchHealthIssues(headers: HttpHeaders): HealthIssuesResponse? =
        post("/api/HealthIssues/LoadHealthIssuesData", mapOf("isHealthSummary" to false), headers, HealthIssuesResponse::class.java)

    // ---- Visits ----
    fun fetchVisitsHeader(headers: HttpHeaders): VisitsHeaderResponse? =
        post("/api/health-summary/FetchH2GHeader", emptyMap<String, Any>(), headers, VisitsHeaderResponse::class.java)

    fun fetchPastVisitDetails(csn: String, organizationId: String?, headers: HttpHeaders): PastVisitDetailsResponse? =
        post(
            "/api/visits/past-details/GetVisitDetailsPast",
            mapOf("csn" to csn, "eorgID" to (organizationId ?: "")),
            headers, PastVisitDetailsResponse::class.java
        )

    fun fetchVisitNotes(csn: String, headers: HttpHeaders): VisitNotesResponse? =
        post(
            "/api/visit-notes/GetVisitNotes",
            mapOf("CSN" to csn, "FromPvdPage" to true),
            headers, VisitNotesResponse::class.java
        )

    fun fetchReportContent(report: ReportRef, csn: String, headers: HttpHeaders): ReportContentResponse? =
        post(
            "/api/report-content/LoadReportContent",
            mapOf(
                "reportID" to (report.reportID ?: ""),
                "reportMnemonic" to (report.reportMnemonic ?: ""),
                "contextID" to (report.reportContext ?: ""),
                "contextINI" to "",
                "csn" to csn,
                "isFullReportPage" to false,
                "nonce" to "",
                "uniqueClass" to "",
                "assumedVariables" to emptyMap<String, Any>()
            ),
            headers, ReportContentResponse::class.java
        )

    // ---- Referrals ----
    fun fetchReferrals(headers: HttpHeaders): ReferralListResponse? =
        post("/api/referrals/listReferrals", emptyMap<String, Any>(), headers, ReferralListResponse::class.java)

    fun fetchReferralDetails(internalId: String, headers: HttpHeaders): ReferralDetailsResponse? =
        post(
            "/api/referrals/getReferralDetails",
            mapOf("RflId" to internalId, "GetFullRFL" to true),
            headers, ReferralDetailsResponse::class.java
        )

    // ---- transport ----

    private fun <T> get(path: String, incoming: HttpHeaders, type: Class<T>): T? {
        val uri = URI.create("$base$path?noCache=${URLEncoder.encode(System.currentTimeMillis().toString(), UTF_8)}")
        val builder = HttpRequest.newBuilder(guard(path, uri)).GET()
        return send(path, builder, incoming, type)
    }

    private fun <T> post(path: String, body: Any, incoming: HttpHeaders, type: Class<T>): T? {
        val builder = HttpRequest.newBuilder(guard(path, URI.create("$base$path")))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), UTF_8))
        return send(path, builder, incoming, type)
    }

    private fun guard(path: String, uri: URI): URI {
        require(path in READ_ONLY_ENDPOINTS) { "MyChart endpoint $path is not on the read-only allowlist" }
        return uri
    }

    private fun <T> send(path: String, builder: HttpRequest.Builder, incoming: HttpHeaders, type: Class<T>): T? {
        builder.timeout(props.readTimeout)
        outgoingHeaders(incoming).forEach { (name, value) -> builder.header(name, value) }
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: java.io.IOException) {
            log.warn("MyChart {} unreachable: {}", path, e.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "MyChart upstream unreachable")
        }
        val status = response.statusCode()
        val body = response.body().orEmpty()
        if (status in 300..399) {
            // MyChart redirects to its login page when the session cookie is missing/expired.
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "MyChart rejected the request to $path (redirect to login). Send current Cookie and __RequestVerificationToken headers from a logged-in MinSundhedsplatform session.")
        }
        if (status >= 400) {
            log.warn("MyChart {} failed with status {}", path, status)
            throw ResponseStatusException(HttpStatus.valueOf(status), "MyChart $path returned $status")
        }
        if (body.isBlank()) return null
        val contentType = response.headers().firstValue("content-type").orElse("")
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<") || contentType.contains("html", ignoreCase = true)) {
            // An HTML page with status 200 is the login/timeout page, not data.
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "MyChart returned its login page for $path. Send current Cookie and __RequestVerificationToken headers from a logged-in MinSundhedsplatform session.")
        }
        return try {
            json.readValue(body, type)
        } catch (e: Exception) {
            log.warn("Failed to parse MyChart {} response: {}", path, e.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected MyChart response for $path")
        }
    }

    internal fun outgoingHeaders(incoming: HttpHeaders): Map<String, String> {
        val out = linkedMapOf<String, String>()
        incoming.forEach { (name, values) ->
            val lower = name.lowercase()
            if (lower in forwardedHeaderNames && values.isNotEmpty()) {
                out[lower] = values.joinToString(if (lower == "cookie") "; " else ", ")
            }
        }
        fallbackHeaders.forEach { (name, value) -> out.putIfAbsent(name, value) }
        out.putIfAbsent("accept", "application/json")
        out.putIfAbsent("x-requested-with", "XMLHttpRequest")
        // The JDK client manages these itself and rejects them as restricted.
        RESTRICTED.forEach { out.remove(it) }
        return out
    }

    companion object {
        private val RESTRICTED = setOf("host", "connection", "content-length", "expect", "upgrade")

        val READ_ONLY_ENDPOINTS: Set<String> = setOf(
            "/ProxySwitch",
            "/api/conversations/GetOrganizations",
            "/api/test-results/GetList",
            "/api/test-results/GetDetailsByCSN",
            "/api/test-results/GetInpatientDetailsByCSN",
            "/api/track-my-health/GetFlowsheets",
            "/api/track-my-health/GetFlowsheetReadings",
            "/api/HealthIssues/LoadHealthIssuesData",
            "/api/health-summary/FetchH2GHeader",
            "/api/visits/past-details/GetVisitDetailsPast",
            "/api/visit-notes/GetVisitNotes",
            "/api/report-content/LoadReportContent",
            "/api/referrals/listReferrals",
            "/api/referrals/getReferralDetails"
        )
    }
}
