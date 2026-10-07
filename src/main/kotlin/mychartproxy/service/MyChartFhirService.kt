package mychartproxy.service

import ca.uhn.fhir.context.FhirContext
import mychartproxy.dhroxy.mapper.*
import mychartproxy.dhroxy.model.ImagingReferralResponse
import mychartproxy.adapter.MyChartAdapter
import mychartproxy.adapter.MyChartAdapter.ResultSource
import mychartproxy.adapter.MyChartAdapter.VisitDocuments
import mychartproxy.client.MyChartClient
import mychartproxy.config.MyChartClientProperties
import mychartproxy.model.*
import org.hl7.fhir.r4.model.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * FHIR facade over Sundhedsplatformen. Every search fetches MyChart data, adapts it to the
 * sundhed.dk DTOs and maps it with dhroxy's mappers (vendored, unchanged).
 */
@Service
class MyChartFhirService(
    private val client: MyChartClient,
    private val props: MyChartClientProperties,
    fhirContext: FhirContext,
    private val patientMapper: PatientMapper,
    private val labMapper: LabMapper,
    private val homeMeasurementMapper: HomeMeasurementMapper,
    private val conditionMapper: ConditionMapper,
    private val encounterMapper: EncounterMapper,
    private val appointmentMapper: AppointmentMapper,
    private val documentReferenceMapper: DocumentReferenceMapper,
    private val referralMapper: ReferralMapper,
    private val organizationMapper: OrganizationMapper,
    private val imagingMapper: ImagingMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of(props.zoneId)
    private val adapter = MyChartAdapter(zone)
    private val provenance = MyChartProvenance(fhirContext, props)

    // ---------------------------------------------------------------- Patient

    fun patients(headers: HttpHeaders, name: String?, identifier: String?, identifierSystem: String?, requestUrl: String): Bundle {
        val selection = adapter.toPersonSelection(client.fetchProxySwitch(headers))
        val filtered = selection.copy(personDelegationData = selection.personDelegationData.filter { person ->
            // Same semantics as PatientService: identifier search is by CPR, which MyChart
            // does not expose, so a CPR query yields no match rather than a wrong one.
            val supportedSystem = identifierSystem == null || identifierSystem == DanishFhir.CPR_SYSTEM || identifierSystem == "urn:dk:cpr"
            val matchesId = supportedSystem && (identifier.isNullOrBlank() ||
                (DanishFhir.normalizeCpr(identifier) != null && DanishFhir.normalizeCpr(person.cpr) == DanishFhir.normalizeCpr(identifier)))
            val matchesName = name.isNullOrBlank() || person.name?.contains(name, ignoreCase = true) == true
            matchesId && matchesName
        })
        return provenance.apply(patientMapper.toPatientBundle(filtered, requestUrl))
    }

    // ---------------------------------------------------------------- Labs / imaging

    fun labObservations(headers: HttpHeaders, fra: String?, til: String?, omraade: String?, requestUrl: String): Bundle {
        // Same default window as SundhedClient.fetchLabsvar: last 6 months.
        val today = LocalDate.now(zone)
        val from = parseBound(fra, endOfDay = false) ?: today.minusMonths(6).atStartOfDay(zone).toOffsetDateTime()
        val to = parseBound(til, endOfDay = true) ?: today.plusDays(1).atStartOfDay(zone).toOffsetDateTime().minusSeconds(1)
        val sources = resultSources(headers) { group ->
            val date = adapter.dates.toOffsetDateTime(group.sortDate)
            date == null || (!date.isBefore(from) && !date.isAfter(to))
        }.filter { matchesOmraade(it, omraade) }
        return provenance.apply(labMapper.toObservationBundle(adapter.toLabsvar(sources), requestUrl))
    }

    fun diagnosticReports(headers: HttpHeaders, identifier: String?, study: String?, requestUrl: String): Bundle =
        provenance.apply(imagingMapper.toDiagnosticReportBundle(imaging(headers, identifier, study), requestUrl))

    fun imagingStudies(headers: HttpHeaders, identifier: String?, study: String?, requestUrl: String): Bundle =
        provenance.apply(imagingMapper.toImagingStudyBundle(imaging(headers, identifier, study), requestUrl))

    private fun imaging(headers: HttpHeaders, identifier: String?, study: String?): List<ImagingReferralResponse> =
        adapter.toImagingResponses(resultSources(headers) { true }).filter { response ->
            val svar = response.svar.firstOrNull()
            (identifier.isNullOrBlank() || identifier == svar?.id || identifier == response.id) &&
                (study.isNullOrBlank() || svar?.undersoegelser.orEmpty().any { it.id == study })
        }

    /** test-results/GetList, then one detail call per result group (bounded by maxDetailCalls). */
    private fun resultSources(headers: HttpHeaders, includeGroup: (ResultGroup) -> Boolean): List<ResultSource> {
        val list = client.fetchTestResultList(headers, props.testResultsMaxResults) ?: return emptyList()
        var budget = props.maxDetailCalls
        val sources = mutableListOf<ResultSource>()
        list.newResultGroups.filter(includeGroup).forEach { group ->
            val groupKey = group.key
            val wanted = group.resultList.ifEmpty { listOfNotNull(groupKey) }
            val details: List<ResultDetail>? = when {
                groupKey == null || budget <= 0 -> null
                group.isInpatient == true -> group.resultList.flatMap { orderKey ->
                    if (budget-- <= 0) emptyList()
                    else optional { client.fetchInpatientResultDetails(orderKey, groupKey, group.organizationID, headers) }?.results.orEmpty()
                }.takeIf { it.isNotEmpty() }
                else -> { budget--; optional { client.fetchTestResultDetails(groupKey, group.organizationID, headers) }?.results }
            }
            val byKey = details.orEmpty().associateBy { it.key }
            // Keep every listed result: the detail if we have it, otherwise the list summary.
            wanted.forEach { key ->
                val detail = byKey[key] ?: adapter.summaryAsDetail(key, list.newResults[key])
                sources += ResultSource(group, detail)
            }
            details.orEmpty().filter { it.key !in wanted }.forEach { sources += ResultSource(group, it) }
        }
        return sources
    }

    private fun matchesOmraade(source: ResultSource, omraade: String?): Boolean {
        if (omraade.isNullOrBlank() || omraade == "Alle") return true
        val type = source.detail.orderMetadata?.resultType?.lowercase() ?: return false
        return when (omraade) {
            "Mikrobiologi" -> "mikro" in type || "micro" in type
            "Patologi" -> "patolog" in type || "patholog" in type
            "KliniskBiokemi" -> "biokemi" in type || "chem" in type || "lab" in type
            else -> true
        }
    }

    // ---------------------------------------------------------------- Vital signs

    fun vitalSigns(headers: HttpHeaders, requestUrl: String): Bundle {
        val sheets = client.fetchFlowsheets(headers)?.flowsheets.orEmpty()
        var budget = props.maxDetailCalls
        val withReadings = sheets.map { sheet ->
            val episode = sheet.episodeId
            if (sheet.readings.isNotEmpty() || episode == null || budget-- <= 0) sheet
            else optional { client.fetchFlowsheetReadings(episode, headers) }?.flowsheet
                ?.let { full -> full.copy(rows = full.rows.ifEmpty { sheet.rows }, name = full.name ?: sheet.name) }
                ?: sheet
        }
        return provenance.apply(homeMeasurementMapper.toBundle(adapter.toHomeMeasurements(withReadings), requestUrl))
    }

    // ---------------------------------------------------------------- Conditions

    fun conditions(headers: HttpHeaders, requestUrl: String): Bundle {
        val forloeb = adapter.toForloebForConditions(client.fetchHealthIssues(headers))
        return provenance.apply(conditionMapper.toBundle(forloeb, null, requestUrl))
    }

    // ---------------------------------------------------------------- Encounters

    fun encounters(headers: HttpHeaders, requestUrl: String): Bundle {
        val visits = adapter.encounterVisits(client.fetchVisitsHeader(headers))
        val (forloeb, kontakter) = adapter.toEncounterInput(visits)
        return provenance.apply(encounterMapper.toBundle(forloeb, kontakter, null, requestUrl))
    }

    // ---------------------------------------------------------------- Appointments

    fun appointments(headers: HttpHeaders, start: String?, end: String?, requestUrl: String): Bundle {
        val payload = adapter.toAppointments(client.fetchVisitsHeader(headers))
        val bundle = appointmentMapper.toAppointmentBundle(payload, requestUrl)
        return provenance.apply(appointmentMapper.filterByDate(bundle, start, end))
    }

    // ---------------------------------------------------------------- Documents

    fun documentReferences(headers: HttpHeaders, requestUrl: String): Bundle {
        val visits = adapter.encounterVisits(client.fetchVisitsHeader(headers))
        var budget = props.maxDetailCalls
        val docs = visits.mapNotNull { visit ->
            val csn = visit.csn ?: return@mapNotNull null
            if (budget <= 0) return@mapNotNull null
            budget -= 1
            val notes = optional { client.fetchVisitNotes(csn, headers) }?.noteList.orEmpty()
            if (notes.isEmpty()) return@mapNotNull null
            budget -= 1
            val details = optional { client.fetchPastVisitDetails(csn, visit.organization?.organizationId, headers) }
            val report = details?.notesInfo?.notesReport?.takeIf { !it.reportID.isNullOrBlank() && details.isEncounterSensitive != true }
            val html = report?.let {
                budget -= 1
                optional { client.fetchReportContent(it, csn, headers) }?.reportContent?.takeIf { c -> c.isNotBlank() }
            }
            VisitDocuments(visit, notes, html)
        }
        val (forloeb, notater) = adapter.toDocumentInput(docs)
        return provenance.apply(documentReferenceMapper.toBundle(forloeb, emptyMap(), notater, null, requestUrl))
    }

    // ---------------------------------------------------------------- Referrals

    fun serviceRequests(headers: HttpHeaders, requestUrl: String): Bundle {
        val list = client.fetchReferrals(headers)
        var budget = props.maxDetailCalls
        val details = list?.referralList.orEmpty().mapNotNull { ref ->
            val id = ref.internalId ?: return@mapNotNull null
            if (budget-- <= 0) return@mapNotNull null
            optional { client.fetchReferralDetails(id, headers) }?.let { id to it }
        }.toMap()
        return provenance.apply(referralMapper.toBundle(adapter.toHenvisninger(list, details), requestUrl))
    }

    // ---------------------------------------------------------------- Organizations

    fun organizations(headers: HttpHeaders, id: Int?, requestUrl: String): Bundle {
        val orgs = client.fetchOrganizations(headers)?.organizations?.values.orEmpty()
        val payload = adapter.toCoreOrganizations(orgs)
        val filtered = payload.copy(organizations = payload.organizations.filter { id == null || it.organizationId == id })
        return provenance.apply(organizationMapper.toOrganizationBundle(filtered, requestUrl))
    }

    // ---------------------------------------------------------------- helpers

    /** Detail lookups enrich a search; their failure must not fail it, but auth errors must surface. */
    private fun <T> optional(block: () -> T?): T? = try {
        block()
    } catch (e: ResponseStatusException) {
        if (e.statusCode.value() == HttpStatus.UNAUTHORIZED.value() || e.statusCode.value() == HttpStatus.FORBIDDEN.value()) throw e
        log.warn("Optional MyChart lookup failed: {}", e.reason)
        null
    }

    private fun parseBound(raw: String?, endOfDay: Boolean): OffsetDateTime? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        val date = value.substringBefore('T')
        val parts = date.split('-')
        val local = runCatching {
            when (parts.size) {
                1 -> if (endOfDay) LocalDate.of(parts[0].toInt(), 12, 31) else LocalDate.of(parts[0].toInt(), 1, 1)
                2 -> java.time.YearMonth.parse(date).let { if (endOfDay) it.atEndOfMonth() else it.atDay(1) }
                else -> LocalDate.parse(date)
            }
        }.getOrNull() ?: return adapter.dates.toOffsetDateTime(value)
        if (value.contains('T')) adapter.dates.toOffsetDateTime(value)?.let { return it }
        return if (endOfDay) local.plusDays(1).atStartOfDay(zone).toOffsetDateTime().minusSeconds(1)
        else local.atStartOfDay(zone).toOffsetDateTime()
    }
}
