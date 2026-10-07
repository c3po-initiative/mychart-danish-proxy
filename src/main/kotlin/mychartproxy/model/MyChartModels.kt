package mychartproxy.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/*
 * Response shapes of the Sundhedsplatformen (Epic MyChart) patient-portal API, as observed
 * in the captured endpoint inventory (minsundhedsplatform.dk, /MyChartPPR1). Only the fields
 * the FHIR mapping needs are modelled; everything else is ignored.
 *
 * `/api/...` endpoints use camelCase JSON. Legacy MVC endpoints (ProxySwitch, Visits/...)
 * use PascalCase and are annotated explicitly.
 */

// ---------- Patient (ProxySwitch) ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxySwitchResponse(
    @JsonProperty("ProxySubjectList")
    val proxySubjectList: List<ProxySubject> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxySubject(
    @JsonProperty("Id")
    val id: String? = null,
    @JsonProperty("DisplayName")
    val displayName: String? = null,
    @JsonProperty("IsSelf")
    val isSelf: Boolean? = null,
    @JsonProperty("IsSelected")
    val isSelected: Boolean? = null,
    @JsonProperty("Disabled")
    val disabled: Boolean? = null
)

// ---------- Shared ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class MyChartOrganization(
    val organizationId: String? = null,
    val organizationName: String? = null,
    val address: List<String> = emptyList(),
    @JsonProperty("isLocal")
    val isLocal: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RichText(
    val contentAsString: String? = null,
    val contentAsHtml: String? = null,
    val hasContent: Boolean? = null
) {
    fun textOrNull(): String? = if (hasContent == false) null
        else (contentAsHtml?.takeIf { it.isNotBlank() } ?: contentAsString?.takeIf { it.isNotBlank() })
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrganizationsResponse(
    val organizations: Map<String, MyChartOrganization> = emptyMap()
)

// ---------- Test results ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class TestResultListResponse(
    val newResultGroups: List<ResultGroup> = emptyList(),
    val newResults: Map<String, ResultSummary> = emptyMap(),
    val areResultsFullyLoaded: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultGroup(
    val key: String? = null,
    val organizationID: String? = null,
    @JsonProperty("isInpatient")
    val isInpatient: Boolean? = null,
    val formattedDate: String? = null,
    val sortDate: String? = null,
    val resultList: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultSummary(
    val key: String? = null,
    val name: String? = null,
    @JsonProperty("isAbnormal")
    val isAbnormal: Boolean? = null,
    val orderMetadata: OrderMetadata? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TestResultDetailsResponse(
    val key: String? = null,
    val formattedEncDate: String? = null,
    val results: List<ResultDetail> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultDetail(
    val key: String? = null,
    val name: String? = null,
    @JsonProperty("isAbnormal")
    val isAbnormal: Boolean? = null,
    val orderMetadata: OrderMetadata? = null,
    val resultComponents: List<ResultComponent> = emptyList(),
    val resultNote: RichText? = null,
    val resultLetter: RichText? = null,
    val studyResult: StudyResult? = null,
    val imageStudies: List<ImageStudyRef> = emptyList()
) {
    /** Imaging/report-type results carry study narrative or images instead of components. */
    fun isImaging(): Boolean =
        imageStudies.isNotEmpty() || (studyResult?.hasStudyContent == true && resultComponents.isEmpty())
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderMetadata(
    val prioritizedInstantISO: String? = null,
    val latestUpdateInstantISO: String? = null,
    val orderProviderName: String? = null,
    val authorizingProviderName: String? = null,
    val readingProviderName: String? = null,
    val resultStatus: String? = null,
    val resultType: String? = null,
    val specimensDisplay: String? = null,
    val resultingLab: ResultingLab? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultingLab(
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultComponent(
    val componentInfo: ComponentInfo? = null,
    val componentResultInfo: ComponentResultInfo? = null,
    val componentComments: RichText? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ComponentInfo(
    val componentID: String? = null,
    val name: String? = null,
    val commonName: String? = null,
    val units: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ComponentResultInfo(
    val value: String? = null,
    val numericValue: BigDecimal? = null,
    val abnormalFlagCategoryValue: String? = null,
    val referenceRange: ReferenceRange? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferenceRange(
    val formattedReferenceRange: String? = null,
    val displayLow: String? = null,
    val displayHigh: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudyResult(
    val hasStudyContent: Boolean? = null,
    val narrative: RichText? = null,
    val impression: RichText? = null,
    val combinedRTFNarrativeImpression: RichText? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageStudyRef(
    val downloadUrl: String? = null
)

// ---------- Track my health (flowsheets) ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlowsheetsResponse(
    val flowsheets: List<Flowsheet> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlowsheetReadingsResponse(
    val flowsheet: Flowsheet? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Flowsheet(
    val episodeId: String? = null,
    val name: String? = null,
    val rows: List<FlowsheetRow> = emptyList(),
    val readings: List<FlowsheetReading> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlowsheetRow(
    val id: String? = null,
    val name: String? = null,
    val unitsDisplayName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlowsheetReading(
    val id: String? = null,
    val rowId: String? = null,
    val instantTakenIso: String? = null,
    val numericValue: BigDecimal? = null,
    val stringValue: String? = null,
    val dataType: String? = null
)

// ---------- Health issues (problem list) ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthIssuesResponse(
    val dataList: List<HealthIssueEntry> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthIssueEntry(
    val healthIssueItem: HealthIssueItem? = null,
    val localItem: HealthIssueItem? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthIssueItem(
    val id: String? = null,
    val name: String? = null,
    val formattedDateNoted: String? = null,
    val organization: MyChartOrganization? = null
)

// ---------- Visits ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class VisitsHeaderResponse(
    val pastVisitsList: List<Visit> = emptyList(),
    val upcomingVisitsList: List<Visit> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Visit(
    val csn: String? = null,
    val id: String? = null,
    val instant: String? = null,
    val durationInMinutes: Int? = null,
    val visitTypeName: String? = null,
    val chiefComplaint: String? = null,
    @JsonProperty("isCanceled")
    val isCanceled: Boolean? = null,
    @JsonProperty("isNoShow")
    val isNoShow: Boolean? = null,
    val leftWithoutSeen: Boolean? = null,
    val inProgress: Boolean? = null,
    @JsonProperty("isTimeToBeDetermined")
    val isTimeToBeDetermined: Boolean? = null,
    val encounterIsEDVisit: Boolean? = null,
    val organization: MyChartOrganization? = null,
    val primaryDepartment: VisitDepartment? = null,
    val primaryProviderName: String? = null,
    val primaryProvider: VisitProvider? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VisitDepartment(
    val id: String? = null,
    val name: String? = null,
    val address: List<String> = emptyList(),
    val phoneNumber: String? = null,
    val specialty: Specialty? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Specialty(
    val title: String? = null,
    val value: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VisitProvider(
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PastVisitDetailsResponse(
    val csn: String? = null,
    @JsonProperty("isEncounterSensitive")
    val isEncounterSensitive: Boolean? = null,
    val notesInfo: NotesInfo? = null,
    val visitSummaryInfo: VisitSummaryInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NotesInfo(
    @JsonProperty("isAtLeastOneNoteShareable")
    val isAtLeastOneNoteShareable: Boolean? = null,
    val notesReport: ReportRef? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportRef(
    val reportContext: String? = null,
    val reportID: String? = null,
    val reportMnemonic: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VisitSummaryInfo(
    val department: String? = null,
    val encounterDate: String? = null,
    val provider: String? = null,
    val visitType: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VisitNotesResponse(
    val noteList: List<VisitNote> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VisitNote(
    val displayName: String? = null,
    val hnoID: String? = null,
    val iso: String? = null,
    @JsonProperty("isNoteSensitive")
    val isNoteSensitive: Boolean? = null,
    @JsonProperty("isAddendum")
    val isAddendum: Boolean? = null,
    val provider: VisitProvider? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportContentResponse(
    val reportContent: String? = null
)

// ---------- Referrals ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralListResponse(
    val referralList: List<ReferralListEntry> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralListEntry(
    val internalId: String? = null,
    val externalId: String? = null,
    val creationDate: String? = null,
    val start: String? = null,
    val end: String? = null,
    val referredByProviderName: String? = null,
    val referredToFacility: String? = null,
    val referredToProviderName: String? = null,
    val status: String? = null,
    val statusString: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralDetailsResponse(
    val internalId: String? = null,
    val nativeReport: ReferralNativeReport? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralNativeReport(
    val type: String? = null,
    val referredBy: ReferralParty? = null,
    val referredTo: ReferralParty? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralParty(
    val provider: ReferralProvider? = null,
    val placeOfService: ReferralPlace? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralProvider(
    val name: String? = null,
    val primarySpecialty: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferralPlace(
    val facility: String? = null,
    val department: String? = null,
    val departmentSpecialty: String? = null
)
