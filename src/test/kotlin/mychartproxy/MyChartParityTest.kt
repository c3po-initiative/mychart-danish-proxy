package mychartproxy

import ca.uhn.fhir.context.FhirContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mychartproxy.dhroxy.mapper.*
import mychartproxy.dhroxy.model.*
import mychartproxy.client.MyChartClient
import mychartproxy.config.MyChartClientProperties
import mychartproxy.service.MyChartFhirService
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ResponseStatusException

/**
 * Sundhedsplatformen data must produce the same FHIR resources as dhroxy does for sundhed.dk.
 * The baseline is dhroxy's own mapper output for the sundhed.dk stub fixtures; the MyChart
 * output for the MyChart stub fixtures must match it in resource type, id scheme,
 * categories and element structure.
 */
class MyChartParityTest {
    private val fhir = FhirContext.forR4()
    private val stub = MyChartStub()
    private val props = MyChartClientProperties(baseUrl = stub.baseUrl, instancePath = MyChartStub.INSTANCE)
    private val service = service(props)
    private val headers = HttpHeaders().apply {
        add("Cookie", "MyChartPPR1=session-abc")
        add("__RequestVerificationToken", "csrf-123")
        add("X-Not-Forwarded", "secret")
    }
    private val url = "http://localhost/fhir/X"

    private val sundhed: JsonNode = javaClass.getResourceAsStream("/dhroxy-baseline/sundhed-fixtures.json")!!.use { ObjectMapper().readTree(it) }
    private val dto = jacksonObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private inline fun <reified T> sundhedFixture(key: String): T = dto.treeToValue(sundhed.get(key), T::class.java)

    @AfterEach
    fun close() = stub.close()

    private fun service(p: MyChartClientProperties) = MyChartFhirService(
        MyChartClient(p), p, fhir, PatientMapper(), LabMapper(), HomeMeasurementMapper(), ConditionMapper(),
        EncounterMapper(), AppointmentMapper(), DocumentReferenceMapper(), ReferralMapper(), OrganizationMapper(), ImagingMapper()
    )

    // ---------------------------------------------------------------- baselines (dhroxy)

    private fun baselines(): Map<String, Bundle> {
        val courses = sundhedFixture<ForloebsoversigtResponse>("courses")
        val key = courses.forloeb.first().idNoegle!!.noegle!!
        return mapOf(
            "Patient" to PatientMapper().toPatientBundle(sundhedFixture("person"), url),
            "Observation" to LabMapper().toObservationBundle(sundhedFixture("labs"), url),
            "Observation/vital-signs" to HomeMeasurementMapper().toBundle(sundhedFixture("measurements"), url),
            "Condition" to ConditionMapper().toBundle(courses, null, url),
            "Encounter" to EncounterMapper().toBundle(courses.forloeb, mapOf(key to sundhedFixture<KontaktperioderResponse>("contacts")), "0101900000", url),
            "Appointment" to AppointmentMapper().toAppointmentBundle(sundhedFixture("appointments"), url),
            "DocumentReference" to DocumentReferenceMapper().toBundle(courses.forloeb, emptyMap(), mapOf(key to sundhedFixture<NotaterResponse>("notes")), "0101900000", url),
            "ServiceRequest" to ReferralMapper().toBundle(sundhedFixture("referrals"), url),
            "Organization" to OrganizationMapper().toOrganizationBundle(sundhedFixture("organization"), url),
            "DiagnosticReport" to ImagingMapper().toDiagnosticReportBundle(sundhedFixture<ImagingReferralResponse>("imagingDetail"), url),
            "ImagingStudy" to ImagingMapper().toImagingStudyBundle(sundhedFixture<ImagingReferralResponse>("imagingDetail"), url)
        )
    }

    private fun myChart(): Map<String, Bundle> = mapOf(
        "Patient" to service.patients(headers, null, null, null, url),
        "Observation" to service.labObservations(headers, null, null, "Alle", url),
        "Observation/vital-signs" to service.vitalSigns(headers, url),
        "Condition" to service.conditions(headers, url),
        "Encounter" to service.encounters(headers, url),
        "Appointment" to service.appointments(headers, null, null, url),
        "DocumentReference" to service.documentReferences(headers, url),
        "ServiceRequest" to service.serviceRequests(headers, url),
        "Organization" to service.organizations(headers, null, url),
        "DiagnosticReport" to service.diagnosticReports(headers, null, null, url),
        "ImagingStudy" to service.imagingStudies(headers, null, null, url)
    )

    // ---------------------------------------------------------------- parity

    @Test
    fun `every resource type is produced with the same type, id scheme and categories as dhroxy`() {
        val base = baselines()
        val mine = myChart()
        base.forEach { (name, expected) ->
            val actual = mine.getValue(name)
            val baseRes = expected.entry.mapNotNull { it.resource }.filter { it.resourceType == expected.entry.first().resource.resourceType }
            val myRes = actual.entry.mapNotNull { it.resource }.filter { it.resourceType == baseRes.first().resourceType }
            assertTrue(myRes.isNotEmpty(), "$name: no resources from MyChart")
            assertEquals(Bundle.BundleType.SEARCHSET, actual.type, name)
            assertEquals(idPrefix(baseRes.first()), idPrefix(myRes.first()), "$name: id scheme")
            assertEquals(categories(baseRes.first()), categories(myRes.first()), "$name: categories")
        }
    }

    @Test
    fun `mychart resources use only element paths the dhroxy mapping also produces`() {
        val base = baselines()
        val mine = myChart()
        val problems = mutableListOf<String>()
        base.forEach { (name, expected) ->
            val type = expected.entry.first().resource.resourceType
            val allowed = expected.entry.filter { it.resource.resourceType == type }.flatMap { paths(it.resource) }.toSet() +
                // Provenance, plus optional elements the shared mapper emits when the source has data
                // that the sundhed.dk fixture happens to lack.
                setOf("meta", "meta.source", "valueString", "referenceRange", "referenceRange.text", "abatementDateTime",
                    "end", "content.attachment.data", "description") +
                // MyChart exposes no CPR. The reference then takes dhroxy's own "unknown patient"
                // form (DanishFhir.patientReference with data-absent-reason), as dhroxy does too.
                PATIENT_UNKNOWN.flatMap { e -> listOf("subject.$e", "patient.$e") }
            val extra = mine.getValue(name).entry.filter { it.resource.resourceType == type }
                .flatMap { paths(it.resource) }.toSet() - allowed
            if (extra.isNotEmpty()) problems += "$name: $extra"
        }
        assertTrue(problems.isEmpty(), "elements not produced by the dhroxy mapping: $problems")
    }

    // ---------------------------------------------------------------- content

    @Test
    fun `lab results map values, units, status and inpatient results`() {
        val obs = service.labObservations(headers, null, null, "Alle", url).entry.map { it.resource as Observation }
        val hgb = obs.single { it.code.text == "Hæmoglobin;B" }
        assertEquals(Observation.ObservationStatus.FINAL, hgb.status)
        assertEquals("8.4", hgb.valueQuantity.value.toPlainString())
        assertEquals("mmol/L", hgb.valueQuantity.code)
        assertEquals("7,3 - 9,5 mmol/L", hgb.referenceRangeFirstRep.text)
        assertEquals("lab-ord-1-1001", hgb.idElement.idPart)
        val culture = obs.single { it.code.text == "Dyrkning" }
        assertEquals("Ingen vækst", culture.valueStringType.value)
        assertEquals(Observation.ObservationStatus.PRELIMINARY, culture.status)
        assertTrue(obs.any { it.code.text == "Natrium;P" }, "inpatient result")
        assertFalse(obs.any { it.code.text == "Røntgen af thorax" }, "imaging is a DiagnosticReport, not a lab Observation")
        val micro = service.labObservations(headers, null, null, "Mikrobiologi", url).entry.map { (it.resource as Observation).code.text }
        assertEquals(listOf("Dyrkning"), micro)
        assertTrue(service.labObservations(headers, "2020-01-01", "2020-12-31", "Alle", url).entry.isEmpty())
    }

    @Test
    fun `imaging result becomes DiagnosticReport with conclusion`() {
        val report = service.diagnosticReports(headers, null, null, url).entry.map { it.resource }.filterIsInstance<DiagnosticReport>().single()
        assertTrue(report.conclusion.contains("Ingen tegn på infiltrater"))
        assertEquals("Røntgenafdelingen", report.performerFirstRep.display)
    }

    @Test
    fun `visits split into encounters and appointments`() {
        val encounters = service.encounters(headers, url).entry.map { it.resource as Encounter }
        assertEquals(1, encounters.size, "cancelled visit is not an encounter")
        assertEquals(Encounter.EncounterStatus.FINISHED, encounters.single().status)
        assertEquals("Region Sjælland - Medicinsk Ambulatorium, Nykøbing F. Sygehus", encounters.single().serviceProvider.display)
        val appts = service.appointments(headers, null, null, url).entry.map { it.resource as Appointment }
        assertEquals(2, appts.size)
        val upcoming = appts.single { it.description == "Opfølgning" }
        assertEquals(30 * 60_000L, upcoming.end.time - upcoming.start.time)
    }

    @Test
    fun `conditions, notes, referrals and organizations`() {
        val conditions = service.conditions(headers, url).entry.map { it.resource as Condition }
        assertEquals(setOf("Astma", "Forhøjet blodtryk"), conditions.map { it.code.text }.toSet())
        assertNotNull(conditions.single { it.code.text == "Forhøjet blodtryk" }.onsetDateTimeType, "Danish month name parsed")

        val doc = service.documentReferences(headers, url).entry.map { it.resource as DocumentReference }.single()
        assertTrue(String(doc.contentFirstRep.attachment.data, Charsets.UTF_8).contains("Syntetisk notat"))
        assertEquals("Ambulant kontrol – Ambulatorienotat", doc.description)

        val srs = service.serviceRequests(headers, url).entry.map { it.resource as ServiceRequest }
        assertEquals(setOf(ServiceRequest.ServiceRequestStatus.ACTIVE, ServiceRequest.ServiceRequestStatus.COMPLETED), srs.map { it.status }.toSet())
        assertEquals("Intern medicin", srs.single { it.status == ServiceRequest.ServiceRequestStatus.ACTIVE }.code.text)

        val org = service.organizations(headers, null, url).entry.map { it.resource as Organization }.single()
        assertEquals("org-1", org.idElement.idPart)
        assertEquals("4200", org.addressFirstRep.postalCode)
        assertEquals("Slagelse", org.addressFirstRep.city)
    }

    @Test
    fun `patients come from the proxy list without inventing a CPR`() {
        val patients = service.patients(headers, null, null, null, url).entry.map { it.resource as Patient }
        assertEquals(2, patients.size)
        assertTrue(patients.all { it.identifier.isEmpty() })
        assertEquals("pat-WP-24abc", patients.first().idElement.idPart)
        assertTrue(service.patients(headers, null, "0101900000", DanishFhir.CPR_SYSTEM, url).entry.isEmpty())
    }

    // ---------------------------------------------------------------- provenance & transport

    @Test
    fun `identifier and code systems name Sundhedsplatformen and meta records the source`() {
        myChart().values.flatMap { b -> b.entry.map { it.resource } }.forEach { resource ->
            val json = fhir.newJsonParser().encodeResourceToString(resource)
            val systems = Regex("\"system\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
            assertFalse(systems.any { it.startsWith("https://www.sundhed.dk/") }, "${resource.resourceType}: $systems")
            assertEquals("${stub.baseUrl}${MyChartStub.INSTANCE}", resource.meta.source)
        }
    }

    @Test
    fun `only allowlisted read endpoints are called and only allowlisted headers forwarded`() {
        myChart()
        val paths = stub.requests.map { it.path }.toSet()
        assertTrue(MyChartClient.READ_ONLY_ENDPOINTS.containsAll(paths), "unexpected upstream calls: ${paths - MyChartClient.READ_ONLY_ENDPOINTS}")
        stub.requests.forEach { r ->
            assertEquals(listOf("MyChartPPR1=session-abc"), r.headers["cookie"])
            assertEquals(listOf("csrf-123"), r.headers["__requestverificationtoken"])
            assertFalse(r.headers.containsKey("x-not-forwarded"))
        }
    }

    @Test
    fun `an HTML login page is reported as an authentication failure`() {
        MyChartStub(MyChartStub.Scenario.LOGIN_PAGE).use { login ->
            val p = MyChartClientProperties(baseUrl = login.baseUrl, instancePath = MyChartStub.INSTANCE)
            val e = runCatching { service(p).conditions(headers, url) }.exceptionOrNull()
            assertTrue(e is ResponseStatusException && e.statusCode.value() == 401, "got $e")
        }
    }

    // ---------------------------------------------------------------- helpers

    private companion object {
        val PATIENT_UNKNOWN = listOf("type", "extension", "extension.url", "extension.valueCode")
    }

    private fun idPrefix(r: Resource): String = r.idElement.idPart.substringBefore('-') + "-" +
        (if (r is DocumentReference) r.idElement.idPart.split('-')[1] + "-" else "")

    private fun categories(r: Resource): Set<String> = when (r) {
        is Observation -> r.category.flatMap { c -> c.coding.map { "${it.system}|${it.code}" } }.toSet()
        is DiagnosticReport -> r.category.flatMap { c -> c.coding.map { "${it.system}|${it.code}" } }.toSet()
        else -> emptySet()
    }

    private fun paths(r: Resource): Set<String> {
        val node = ObjectMapper().readTree(fhir.newJsonParser().encodeResourceToString(r))
        val out = mutableSetOf<String>()
        fun walk(n: JsonNode, prefix: String) {
            when {
                n.isObject -> n.fields().forEach { (k, v) -> val p = if (prefix.isEmpty()) k else "$prefix.$k"; if (!v.isContainerNode) out += p; walk(v, p) }
                n.isArray -> n.forEach { walk(it, prefix) }
            }
        }
        walk(node, "")
        out += node.fieldNames().asSequence().toList()
        return out.filterNot { it == "id" || it == "resourceType" || it.startsWith("text") }.toSet()
    }
}
