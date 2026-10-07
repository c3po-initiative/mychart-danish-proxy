package mychartproxy.adapter

import mychartproxy.dhroxy.model.*
import mychartproxy.model.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Translates Sundhedsplatformen (MyChart) payloads into the sundhed.dk DTOs that dhroxy's
 * mappers consume (vendored under [mychartproxy.dhroxy]). Mapping to FHIR is therefore done
 * by exactly the same code as dhroxy, which keeps resource types, ids, categories, statuses,
 * profiles and DK Core conventions identical between sundhed.dk and Sundhedsplatformen data.
 *
 * Rule of thumb: a MyChart field is only placed in a DTO field with the same meaning.
 * Information without a matching DTO field is dropped rather than smuggled into another one.
 */
class MyChartAdapter(zone: ZoneId) {
    val dates = MyChartDates(zone)

    // ------------------------------------------------------------------ Patient

    fun toPersonSelection(payload: ProxySwitchResponse?): PersonSelectionResponse =
        PersonSelectionResponse(
            personDelegationData = payload?.proxySubjectList.orEmpty()
                .filter { it.disabled != true && !it.displayName.isNullOrBlank() }
                .map { subject ->
                    PersonDelegationData(
                        id = subject.id?.let(::fhirSafe),
                        // MyChart does not expose CPR in any captured endpoint.
                        cpr = null,
                        name = subject.displayName,
                        relationType = if (subject.isSelf == true) "MigSelv" else "Fuldmagt"
                    )
                }
        )

    // ------------------------------------------------------------------ Test results

    data class ResultSource(val group: ResultGroup?, val detail: ResultDetail)

    fun toLabsvar(sources: List<ResultSource>): LabsvarResponse {
        val labs = sources.filterNot { it.detail.isImaging() }
        val rekvisitioner = labs.mapNotNull { (group, detail) ->
            val key = detail.key ?: return@mapNotNull null
            Rekvisition(
                id = key,
                rekvirentsOrganisation = detail.orderMetadata?.authorizingProviderName
                    ?: detail.orderMetadata?.orderProviderName,
                svartidspunkt = dates.toIsoOffset(detail.orderMetadata?.latestUpdateInstantISO)
            )
        }.distinctBy { it.id }
        val resultater = labs.flatMap { (group, detail) -> labResults(group, detail) }
        return LabsvarResponse(Svaroversigt(laboratorieresultater = resultater, rekvisitioner = rekvisitioner))
    }

    private fun labResults(group: ResultGroup?, detail: ResultDetail): List<Laboratorieresultat> {
        val meta = detail.orderMetadata
        val date = dates.toIsoOffset(meta?.prioritizedInstantISO) ?: dates.toIsoOffset(group?.sortDate)
        val status = mapResultStatus(meta?.resultStatus)
        val lab = meta?.resultingLab?.name
        if (detail.resultComponents.isEmpty()) {
            // Text-only result (e.g. a narrative report without discrete components).
            val text = detail.resultNote?.textOrNull() ?: detail.resultLetter?.textOrNull()
                ?: detail.studyResult?.impression?.textOrNull() ?: detail.studyResult?.narrative?.textOrNull()
            return listOf(
                Laboratorieresultat(
                    rekvisitionsId = detail.key,
                    resultatStatuskode = status,
                    resultatdato = date,
                    resultattype = meta?.resultType,
                    konklusionHtml = text,
                    undersoegelser = listOf(Undersoegelse(undersoegelsesNavn = detail.name, eksaminator = lab, materiale = meta?.specimensDisplay))
                )
            )
        }
        return detail.resultComponents.map { component ->
            val info = component.componentInfo
            val result = component.componentResultInfo
            val numeric = numericValue(result)
            Laboratorieresultat(
                analysetypeId = info?.componentID ?: info?.name,
                rekvisitionsId = detail.key,
                resultatStatuskode = status,
                resultatdato = date,
                resultattype = meta?.resultType,
                referenceIntervalTekst = result?.referenceRange?.formattedReferenceRange?.takeIf { it.isNotBlank() },
                vaerdi = result?.value?.takeIf { it.isNotBlank() },
                // A comment is only attached where it cannot displace a textual value.
                konklusionHtml = component.componentComments?.textOrNull()
                    ?.takeIf { numeric != null || result?.value.isNullOrBlank() },
                undersoegelser = listOf(
                    Undersoegelse(
                        analyseKode = info?.componentID,
                        undersoegelsesNavn = info?.name ?: info?.commonName ?: detail.name,
                        eksaminator = lab,
                        materiale = meta?.specimensDisplay,
                        quantitativeFindings = numeric?.let { quantitativeFindings(it, info?.units) }
                    )
                )
            )
        }
    }

    /** sundhed.dk ships values in a table; LabMapper reads value from row 1 col 9, unit col 10. */
    private fun quantitativeFindings(value: BigDecimal, unit: String?): QuantitativeFindings {
        val header = List<Any?>(11) { "" }
        val row = MutableList<Any?>(11) { null }
        row[9] = value.toPlainString()
        row[10] = unit?.takeIf { it.isNotBlank() }
        return QuantitativeFindings(data = listOf(header, row), noColumns = 11, noRows = 2)
    }

    private fun numericValue(result: ComponentResultInfo?): BigDecimal? {
        val text = result?.value?.trim().orEmpty()
        if (text.isNotEmpty()) {
            // Only a value that is itself numeric counts; "<5" or "Negativ" stays text.
            return text.replace(',', '.').toBigDecimalOrNull()
        }
        return null
    }

    internal fun mapResultStatus(status: String?): String? {
        val s = status?.lowercase()?.trim() ?: return null
        return when {
            listOf("annul", "cancel", "slettet").any { it in s } -> "Annulleret"
            listOf("foreløb", "forelob", "prelim", "delvis", "partial", "in process", "igangværende").any { it in s } -> "Foreloebigt"
            listOf("endelig", "final", "afsluttet", "complete", "rettet", "corrected", "edited", "ændret").any { it in s } -> "SvarEndeligt"
            else -> null
        }
    }

    fun toImagingResponses(sources: List<ResultSource>): List<ImagingReferralResponse> =
        sources.filter { it.detail.isImaging() }.map { (group, detail) ->
            val meta = detail.orderMetadata
            val date = dates.toIsoOffset(meta?.prioritizedInstantISO) ?: dates.toIsoOffset(group?.sortDate)
            val producent = meta?.resultingLab?.name?.let { ImagingProducent(navn = it) }
            val description = listOfNotNull(
                plainText(detail.studyResult?.impression),
                plainText(detail.studyResult?.narrative)
            ).ifEmpty { listOfNotNull(plainText(detail.studyResult?.combinedRTFNarrativeImpression), plainText(detail.resultNote)) }
                .joinToString("\n\n").takeIf { it.isNotBlank() }
            ImagingReferralResponse(
                id = group?.key,
                producent = producent,
                rekvirent = (meta?.authorizingProviderName ?: meta?.orderProviderName)?.let { ImagingRekvirent(fornavn = it) },
                svar = listOf(
                    ImagingSvar(
                        beskrivelse = description,
                        dato = date,
                        henvisningsId = group?.key,
                        id = detail.key,
                        laege = meta?.readingProviderName,
                        navn = detail.name,
                        producent = producent,
                        type = meta?.resultType,
                        udgivelsesDato = dates.toIsoOffset(meta?.latestUpdateInstantISO),
                        undersoegelser = listOf(ImagingUndersoegelse(id = detail.key, dato = date, navn = detail.name))
                    )
                )
            )
        }

    /** Wrap list-level summaries as details when a detail call is unavailable. */
    fun summaryAsDetail(key: String, summary: ResultSummary?): ResultDetail =
        ResultDetail(key = summary?.key ?: key, name = summary?.name, isAbnormal = summary?.isAbnormal, orderMetadata = summary?.orderMetadata)

    // ------------------------------------------------------------------ Vital signs

    fun toHomeMeasurements(flowsheets: List<Flowsheet>): HomeMeasurementsResponse =
        HomeMeasurementsResponse(
            documents = flowsheets.flatMap { sheet ->
                val rows = sheet.rows.associateBy { it.id }
                sheet.readings.mapNotNull { reading ->
                    val row = rows[reading.rowId]
                    val value = reading.numericValue?.toPlainString() ?: reading.stringValue?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    HomeMeasurementDocument(
                        type = row?.name,
                        name = sheet.name,
                        date = dates.toIsoOffset(reading.instantTakenIso) ?: reading.instantTakenIso,
                        value = value,
                        unit = row?.unitsDisplayName?.takeIf { it.isNotBlank() },
                        source = sheet.name
                    )
                }
            }
        )

    // ------------------------------------------------------------------ Conditions

    fun toForloebForConditions(payload: HealthIssuesResponse?): ForloebsoversigtResponse =
        ForloebsoversigtResponse(
            forloeb = payload?.dataList.orEmpty()
                .mapNotNull { it.localItem ?: it.healthIssueItem }
                .filter { !it.name.isNullOrBlank() }
                .distinctBy { it.id ?: it.name }
                .map { item ->
                    ForloebEntry(
                        diagnoseNavn = item.name,
                        idNoegle = item.id?.let { NoegleRef(noegle = it) },
                        datoFra = dates.toIsoOffset(item.formattedDateNoted),
                        sygehusNavn = item.organization?.organizationName
                    )
                }
        )

    // ------------------------------------------------------------------ Encounters

    fun encounterVisits(payload: VisitsHeaderResponse?): List<Visit> =
        payload?.pastVisitsList.orEmpty()
            .filter { !it.csn.isNullOrBlank() && it.isCanceled != true && it.isNoShow != true && it.leftWithoutSeen != true }
            .distinctBy { it.csn }

    fun toEncounterInput(visits: List<Visit>): Pair<List<ForloebEntry>, Map<String, KontaktperioderResponse>> {
        val forloeb = visits.map { ForloebEntry(idNoegle = NoegleRef(noegle = it.csn)) }
        val kontakter = visits.associate { visit ->
            visit.csn!! to KontaktperioderResponse(
                listOf(
                    KontaktperiodeEntry(
                        noegle = visit.csn,
                        datoFra = dates.toIsoOffset(visit.instant),
                        status = if (visit.inProgress == true) "InProgress" else "Finished",
                        enhedsInformation = enhed(visit),
                        laegeligAnsvarlig = visit.primaryProviderName ?: visit.primaryProvider?.name
                    )
                )
            )
        }
        return forloeb to kontakter
    }

    private fun enhed(visit: Visit): EnhedsInformation? {
        val org = visit.organization
        val dept = visit.primaryDepartment
        if (org == null && dept == null) return null
        return EnhedsInformation(
            sygehusKode = org?.organizationId,
            afdelingsKode = dept?.id,
            institution = org?.organizationName,
            afdeling = dept?.name
        )
    }

    // ------------------------------------------------------------------ Appointments

    fun toAppointments(payload: VisitsHeaderResponse?): AppointmentsResponse {
        val visits = (payload?.upcomingVisitsList.orEmpty() + payload?.pastVisitsList.orEmpty())
            .filter { it.isCanceled != true && !(it.csn ?: it.id).isNullOrBlank() }
            .distinctBy { it.csn ?: it.id }
        return AppointmentsResponse(appointments = visits.map { visit ->
            val start = dates.toOffsetDateTime(visit.instant)
            val end = start?.let { s -> visit.durationInMinutes?.takeIf { it > 0 }?.let { s.plusMinutes(it.toLong()) } }
            val dept = visit.primaryDepartment
            val provider = visit.primaryProviderName ?: visit.primaryProvider?.name
            AppointmentItem(
                documentId = visit.csn ?: visit.id,
                appointmentType = visit.visitTypeName,
                title = visit.visitTypeName,
                startTime = start?.toString(),
                endTime = end?.toString(),
                endTimeNotDefined = end == null,
                location = dept?.let {
                    LocationDetailed(
                        organisation = it.name ?: visit.organization?.organizationName,
                        address = it.address.takeIf { a -> a.isNotEmpty() }?.let { a -> AddressDetailed(formatted = a.joinToString(", ")) },
                        phone = it.phoneNumber,
                        ward = it.specialty?.title
                    )
                },
                performer = provider?.let { PerformerDetailed(organisation = it) }
            )
        })
    }

    // ------------------------------------------------------------------ Documents

    data class VisitDocuments(val visit: Visit, val notes: List<VisitNote>, val reportHtml: String?)

    fun toDocumentInput(docs: List<VisitDocuments>): Pair<List<ForloebEntry>, Map<String, NotaterResponse>> {
        val usable = docs.filter { it.visit.csn != null && (it.reportHtml != null || it.notes.isNotEmpty()) }
        val forloeb = usable.map { ForloebEntry(idNoegle = NoegleRef(noegle = it.visit.csn)) }
        val notater = usable.associate { doc ->
            val visit = doc.visit
            val visibleNotes = doc.notes.filter { it.isNoteSensitive != true }
            val entries = if (doc.reportHtml != null) {
                // The visit's notes report renders all shareable notes as one document.
                listOf(
                    NotatEntry(
                        notatType = "Notat",
                        datoFra = dates.toIsoOffset(visit.instant) ?: visibleNotes.firstNotNullOfOrNull { dates.toIsoOffset(it.iso) },
                        enhedsInformation = enhed(visit),
                        overskrift = listOfNotNull(visit.visitTypeName, visibleNotes.mapNotNull { it.displayName }.distinct().joinToString(", ").takeIf { it.isNotBlank() })
                            .joinToString(" – ").takeIf { it.isNotBlank() },
                        broedtekst = doc.reportHtml,
                        behandlerNavn = visibleNotes.mapNotNull { it.provider?.name }.distinct().joinToString(", ").takeIf { it.isNotBlank() }
                    )
                )
            } else {
                visibleNotes.map { note ->
                    NotatEntry(
                        notatType = "Notat",
                        datoFra = dates.toIsoOffset(note.iso),
                        enhedsInformation = enhed(visit),
                        overskrift = note.displayName,
                        behandlerNavn = note.provider?.name
                    )
                }
            }
            visit.csn!! to NotaterResponse(notater = entries)
        }
        return forloeb to notater
    }

    // ------------------------------------------------------------------ Referrals

    fun toHenvisninger(
        list: ReferralListResponse?,
        details: Map<String, ReferralDetailsResponse>,
        now: OffsetDateTime = OffsetDateTime.now()
    ): HenvisningerResponse {
        val active = mutableListOf<HenvisningEntry>()
        val previous = mutableListOf<HenvisningEntry>()
        list?.referralList.orEmpty().forEach { ref ->
            val native = ref.internalId?.let { details[it] }?.nativeReport
            val end = dates.toOffsetDateTime(ref.end)
            val entry = HenvisningEntry(
                henvisningsDato = dates.toIsoOffset(ref.start) ?: dates.toIsoOffset(ref.creationDate),
                udloebsDato = end?.toString(),
                henvisendeKlinik = native?.referredBy?.placeOfService?.let { it.facility ?: it.department }
                    ?: ref.referredByProviderName,
                specialeNavn = native?.referredTo?.placeOfService?.departmentSpecialty
                    ?: native?.referredTo?.provider?.primarySpecialty
                    ?: ref.referredToFacility,
                detaljer = HenvisningDetaljer(
                    henvisningsType = native?.type,
                    modtager = (ref.referredToProviderName ?: ref.referredToFacility
                        ?: native?.referredTo?.placeOfService?.facility)?.let { HenvisningModtager(name = it) }
                )
            )
            if (isClosedReferral(ref.statusString, end, now)) previous += entry else active += entry
        }
        return HenvisningerResponse(aktiveHenvisninger = active, tidligereHenvisninger = previous)
    }

    private fun isClosedReferral(status: String?, end: OffsetDateTime?, now: OffsetDateTime): Boolean {
        if (end != null && end.isBefore(now)) return true
        val s = status?.lowercase() ?: return false
        return listOf("afsluttet", "lukket", "closed", "annul", "cancel", "afvist", "denied", "udløbet", "expired", "completed")
            .any { it in s }
    }

    // ------------------------------------------------------------------ Organizations

    fun toCoreOrganizations(orgs: Collection<MyChartOrganization>): CoreOrganizationResponse =
        CoreOrganizationResponse(
            organizations = orgs
                .filter { !it.organizationId.isNullOrBlank() || !it.organizationName.isNullOrBlank() }
                .distinctBy { it.organizationId ?: it.organizationName }
                .map { org ->
                    val zipLine = org.address.lastOrNull { ZIP_CITY.matches(it.trim()) }
                    val zip = zipLine?.let { ZIP_CITY.find(it.trim()) }
                    CoreOrganization(
                        organizationId = organizationNumber(org.organizationId ?: org.organizationName!!),
                        name = org.organizationName,
                        displayName = org.organizationName,
                        street = org.address.filter { it != zipLine && it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
                        zipCode = zip?.groupValues?.get(1)?.toIntOrNull(),
                        city = zip?.groupValues?.get(2)
                    )
                }
        )

    companion object {
        private val ZIP_CITY = Regex("^(\\d{4})\\s+(.+)$")

        /**
         * dhroxy's Organization DTO uses an integer id. MyChart organization ids are strings;
         * numeric ones are kept, others get a stable non-negative hash.
         */
        fun organizationNumber(raw: String): Int = raw.trim().toIntOrNull()?.takeIf { it >= 0 }
            ?: (raw.trim().hashCode() and Int.MAX_VALUE)

        /** FHIR id-safe form of an opaque MyChart key. */
        fun fhirSafe(raw: String): String = raw.replace(Regex("[^A-Za-z0-9.-]+"), "-").trim('-').take(60)

        private fun plainText(text: RichText?): String? {
            if (text == null || text.hasContent == false) return null
            text.contentAsString?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            return text.contentAsHtml?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                ?.replace(Regex("<[^>]+>"), "")?.replace("&nbsp;", " ")?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}
