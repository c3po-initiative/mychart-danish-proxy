package mychartproxy.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Upstream settings for Sundhedsplatformen (Epic MyChart, "MinSundhedsplatform").
 *
 * The FHIR server is mounted at [fhirPath] (default `/fhir`). Like dhroxy it is a stateless
 * pass-through: the caller supplies the MyChart session headers with every request.
 */
@ConfigurationProperties("mychart.client")
data class MyChartClientProperties(
    /** Servlet path of the FHIR server (no trailing slash/wildcard). */
    val fhirPath: String = "/fhir",
    val baseUrl: String = "https://www.minsundhedsplatform.dk",
    /** MyChart instance prefix seen in the captured traffic. */
    val instancePath: String = "/MyChartPPR1",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    val forwardedHeaders: List<String> = listOf(
        "cookie",
        "__requestverificationtoken",
        "user-agent",
        "accept-language",
        "referer"
    ),
    /** Used only when the incoming request does not carry the header. Keys are lowercase. */
    val fallbackHeaders: Map<String, String> = emptyMap(),
    /** Upper bound on per-visit/per-result detail calls in one FHIR search. */
    val maxDetailCalls: Int = 40,
    /**
     * `groupType` sent to test-results/GetList. The captured inventory records only that it
     * is a string; adjust if the instance groups results differently.
     */
    val testResultsGroupType: String = "0",
    /** maxResults sent to test-results/GetList. */
    val testResultsMaxResults: Int = 100,
    /** Number of flowsheet readings requested per flowsheet (vital signs). */
    val flowsheetReadings: Int = 100,
    /** Time zone used to interpret MyChart dates that carry no offset. */
    val zoneId: String = "Europe/Copenhagen",
    /**
     * Rewrite `https://www.sundhed.dk/...` identifier and coding systems emitted by the
     * shared mappers to [sourceNamespace], so business identifiers name the real issuer.
     * Resource structure, ids, profiles and categories stay identical to dhroxy's.
     */
    val rewriteNamespaces: Boolean = true,
    val sourceNamespace: String = "https://www.minsundhedsplatform.dk/"
)
