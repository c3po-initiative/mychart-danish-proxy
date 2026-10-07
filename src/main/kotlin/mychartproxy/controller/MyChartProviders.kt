package mychartproxy.controller

import ca.uhn.fhir.rest.annotation.OptionalParam
import ca.uhn.fhir.rest.annotation.Search
import ca.uhn.fhir.rest.api.server.IBundleProvider
import ca.uhn.fhir.rest.param.DateRangeParam
import ca.uhn.fhir.rest.param.StringParam
import ca.uhn.fhir.rest.param.TokenAndListParam
import ca.uhn.fhir.rest.param.TokenOrListParam
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.IResourceProvider
import ca.uhn.fhir.rest.server.SimpleBundleProvider
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails
import mychartproxy.dhroxy.mapper.DanishFhir
import mychartproxy.service.MyChartFhirService
import org.hl7.fhir.r4.model.*
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Resource providers for the Sundhedsplatformen FHIR server. They mirror the search
 * parameters of dhroxy's providers for the same resource types.
 */
interface MyChartResourceProvider : IResourceProvider

internal object MyChartRequest {
    fun headers(details: ServletRequestDetails): HttpHeaders = HttpHeaders().apply {
        details.headers?.forEach { (k, v) -> addAll(k, v) }
    }

    fun url(details: ServletRequestDetails): String =
        details.servletRequest?.let { req ->
            buildString {
                append(req.requestURL.toString())
                req.queryString?.let { append("?").append(it) }
            }
        } ?: (details.requestPath ?: "")

    /** Same ge/gt/le/lt/eq interpretation as dhroxy's Observation and Appointment providers. */
    fun dateBounds(range: DateRangeParam?, raw: Array<String>?): Pair<String?, String?> {
        val values = if (range != null) listOfNotNull(
            range.lowerBound?.let { (it.prefix?.value?.lowercase().orEmpty()) + (it.valueAsString ?: "") },
            range.upperBound?.let { (it.prefix?.value?.lowercase().orEmpty()) + (it.valueAsString ?: "") }
        ).toTypedArray() else raw
        var start: String? = null
        var end: String? = null
        values.orEmpty().forEach { v ->
            when {
                v.startsWith("ge") -> start = v.removePrefix("ge")
                v.startsWith("gt") -> start = v.removePrefix("gt")
                v.startsWith("le") -> end = v.removePrefix("le")
                v.startsWith("lt") -> end = v.removePrefix("lt")
                v.startsWith("eq") -> { start = v.removePrefix("eq"); end = v.removePrefix("eq") }
                start == null -> start = v
                end == null -> end = v
            }
        }
        return start to end
    }

    fun tokens(param: Any?): List<TokenParam> {
        val out = mutableListOf<TokenParam>()
        fun walk(item: Any?) {
            when (item) {
                is TokenParam -> out += item
                is TokenOrListParam -> walk(item.valuesAsQueryTokens)
                is TokenAndListParam -> walk(item.valuesAsQueryTokens)
                is Iterable<*> -> item.forEach(::walk)
            }
        }
        walk(param)
        return out
    }

    /**
     * Runs a search and translates upstream failures into proper FHIR HTTP statuses
     * (401/403 for session problems, 502 for upstream errors) instead of HAPI's generic 500.
     */
    inline fun <reified T : Resource> resources(search: () -> Bundle): IBundleProvider {
        val bundle = try {
            search()
        } catch (e: ResponseStatusException) {
            throw toFhirException(e)
        }
        return SimpleBundleProvider(bundle.entry.mapNotNull { it.resource as? T })
    }

    fun toFhirException(e: ResponseStatusException): BaseServerResponseException {
        val message = e.reason ?: "MyChart request failed"
        return when (e.statusCode.value()) {
            401 -> AuthenticationException(message)
            403 -> ForbiddenOperationException(message)
            else -> UnclassifiedServerFailureException(502, message)
        }
    }
}

@Component
class MyChartPatientProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<Patient> = Patient::class.java

    @Search
    fun search(
        @OptionalParam(name = Patient.SP_NAME) name: StringParam?,
        @OptionalParam(name = Patient.SP_IDENTIFIER) identifier: TokenParam?,
        details: ServletRequestDetails
    ): IBundleProvider = MyChartRequest.resources<Patient> { service.patients(MyChartRequest.headers(details), name?.value, identifier?.value, identifier?.system, MyChartRequest.url(details)) }
}

@Component
class MyChartObservationProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<Observation> = Observation::class.java

    @Search
    fun search(
        @OptionalParam(name = Observation.SP_DATE) date: DateRangeParam?,
        @OptionalParam(name = Observation.SP_CATEGORY) category: TokenAndListParam?,
        details: ServletRequestDetails
    ): IBundleProvider {
        val categoryValue = MyChartRequest.tokens(category).firstOrNull()?.value?.takeIf { it.isNotBlank() }
            ?: details.parameters["category"]?.firstOrNull()
        val headers = MyChartRequest.headers(details)
        val url = MyChartRequest.url(details)
        val normalized = categoryValue?.lowercase().orEmpty()
        if (normalized == "vital-signs" || normalized == "vitalsigns" || normalized.contains("hjemmemåling")) {
            return MyChartRequest.resources<Observation> { service.vitalSigns(headers, url) }
        }
        val (fra, til) = MyChartRequest.dateBounds(date, details.parameters["date"])
        val omraade = when {
            normalized.isBlank() -> "Alle"
            normalized.contains("mikro") -> "Mikrobiologi"
            normalized.contains("patologi") -> "Patologi"
            normalized.contains("klinisk") || normalized.contains("biokemi") -> "KliniskBiokemi"
            else -> "Alle"
        }
        return MyChartRequest.resources<Observation> { service.labObservations(headers, fra, til, omraade, url) }
    }
}

@Component
class MyChartDiagnosticReportProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<DiagnosticReport> = DiagnosticReport::class.java

    @Search
    fun search(
        @OptionalParam(name = DiagnosticReport.SP_IDENTIFIER) identifier: TokenParam?,
        @OptionalParam(name = "study") study: TokenParam?,
        details: ServletRequestDetails
    ): IBundleProvider = MyChartRequest.resources<DiagnosticReport> { service.diagnosticReports(MyChartRequest.headers(details), identifier?.value, study?.value, MyChartRequest.url(details)) }
}

@Component
class MyChartImagingStudyProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<ImagingStudy> = ImagingStudy::class.java

    @Search
    fun search(
        @OptionalParam(name = ImagingStudy.SP_IDENTIFIER) identifier: TokenParam?,
        @OptionalParam(name = "study") study: TokenParam?,
        details: ServletRequestDetails
    ): IBundleProvider = MyChartRequest.resources<ImagingStudy> { service.imagingStudies(MyChartRequest.headers(details), identifier?.value, study?.value, MyChartRequest.url(details)) }
}

@Component
class MyChartConditionProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<Condition> = Condition::class.java

    @Search
    fun search(details: ServletRequestDetails): IBundleProvider =
        MyChartRequest.resources<Condition> { service.conditions(MyChartRequest.headers(details), MyChartRequest.url(details)) }
}

@Component
class MyChartEncounterProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<Encounter> = Encounter::class.java

    @Search
    fun search(details: ServletRequestDetails): IBundleProvider =
        MyChartRequest.resources<Encounter> { service.encounters(MyChartRequest.headers(details), MyChartRequest.url(details)) }
}

@Component
class MyChartAppointmentProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<Appointment> = Appointment::class.java

    @Search
    fun search(
        @OptionalParam(name = Appointment.SP_DATE) date: DateRangeParam?,
        details: ServletRequestDetails
    ): IBundleProvider {
        val (start, end) = MyChartRequest.dateBounds(date, details.parameters["date"])
        return MyChartRequest.resources<Appointment> { service.appointments(MyChartRequest.headers(details), start, end, MyChartRequest.url(details)) }
    }
}

@Component
class MyChartDocumentReferenceProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<DocumentReference> = DocumentReference::class.java

    @Search
    fun search(details: ServletRequestDetails): IBundleProvider =
        MyChartRequest.resources<DocumentReference> { service.documentReferences(MyChartRequest.headers(details), MyChartRequest.url(details)) }
}

@Component
class MyChartServiceRequestProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<ServiceRequest> = ServiceRequest::class.java

    @Search
    fun search(details: ServletRequestDetails): IBundleProvider =
        MyChartRequest.resources<ServiceRequest> { service.serviceRequests(MyChartRequest.headers(details), MyChartRequest.url(details)) }
}

@Component
class MyChartOrganizationProvider(private val service: MyChartFhirService) : MyChartResourceProvider {
    override fun getResourceType(): Class<Organization> = Organization::class.java

    @Search
    fun search(
        @OptionalParam(name = Organization.SP_IDENTIFIER) identifier: TokenOrListParam?,
        details: ServletRequestDetails
    ): IBundleProvider {
        val tokens = MyChartRequest.tokens(identifier)
        // MyChart organizations carry no CVR; a CVR query cannot match.
        if (tokens.any { it.system == DanishFhir.CVR_SYSTEM || it.system == "urn:dk:cvr" } && tokens.none { it.system == null }) {
            return SimpleBundleProvider(emptyList<Organization>())
        }
        val id = tokens.firstOrNull { it.system == null }?.value?.removePrefix("org-")?.toIntOrNull()
        if (tokens.isNotEmpty() && id == null) return SimpleBundleProvider(emptyList<Organization>())
        return MyChartRequest.resources<Organization> { service.organizations(MyChartRequest.headers(details), id, MyChartRequest.url(details)) }
    }
}
