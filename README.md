# mychart-danish-proxy

A read-only FHIR R4 proxy for **Sundhedsplatformen** (Epic MyChart, *MinSundhedsplatform*,
`minsundhedsplatform.dk`). It maps the patient portal's data to **the same FHIR resources as
[dhroxy](https://github.com/c3po-initiative/dhroxy)** produces for sundhed.dk, so a client can
consume both sources with one mapping. The server runs at `/fhir`.

Status: verified against a live MinSundhedsplatform session (all supported searches return 200),
and by a parity test against dhroxy's own mapper output.

## Supported resources and source endpoints

| FHIR | MyChart endpoint(s) | dhroxy mapper |
|---|---|---|
| Patient | `ProxySwitch` | `PatientMapper` |
| Observation (labs) | `api/test-results/GetList` → `GetDetailsByCSN` / `GetInpatientDetailsByCSN` | `LabMapper` |
| Observation `category=vital-signs` | `api/track-my-health/GetFlowsheets` → `GetFlowsheetReadings` | `HomeMeasurementMapper` |
| DiagnosticReport, ImagingStudy | test results with study narrative / images | `ImagingMapper` |
| Condition | `api/HealthIssues/LoadHealthIssuesData` | `ConditionMapper` |
| Encounter | `api/health-summary/FetchH2GHeader` (past visits) | `EncounterMapper` |
| Appointment | `FetchH2GHeader` (upcoming and past visits) | `AppointmentMapper` |
| DocumentReference | `api/visit-notes/GetVisitNotes`, `api/visits/past-details/GetVisitDetailsPast`, `api/report-content/LoadReportContent` | `DocumentReferenceMapper` |
| ServiceRequest | `api/referrals/listReferrals` → `getReferralDetails` | `ReferralMapper` |
| Organization | `api/conversations/GetOrganizations` | `OrganizationMapper` |

Search parameters follow dhroxy: `Observation?date=&category=`, `Appointment?date=`,
`DiagnosticReport|ImagingStudy?identifier=&study=`, `Patient?name=&identifier=`,
`Organization?identifier=`.

Not available from the MyChart traffic captured so far, so not exposed: MedicationStatement,
MedicationRequest, Immunization, CarePlan, `Patient/$summary`, transactions.

## How "the same resources as dhroxy" is guaranteed

MyChart payloads are not mapped to FHIR by new code. `MyChartAdapter` translates them into the
sundhed.dk DTOs dhroxy uses (`LabsvarResponse`, `ForloebsoversigtResponse`,
`AppointmentsResponse`, …), and **dhroxy's own mappers** produce the FHIR. Those mappers and DTOs
are vendored unchanged under `mychartproxy.dhroxy` (see [VENDORED.md](VENDORED.md)). Resource types,
id schemes, categories, statuses, DK Core conventions and search parameters are therefore
dhroxy's.

Two deliberate differences, both provenance:

* `meta.source` is the MyChart instance URL.
* Local identifier/code systems the mappers put under `https://www.sundhed.dk/…` are moved to
  `https://www.minsundhedsplatform.dk/…`, because those values (CSNs, order keys, component ids)
  are issued by Sundhedsplatformen. Disable with `mychart.client.rewrite-namespaces: false`.

`MyChartParityTest` compares the output with dhroxy's mapper output for dhroxy's sundhed.dk stub
fixtures: same resource type, id scheme and categories, and no element dhroxy does not produce.

## Running

Requires JDK 21 (`brew install --cask temurin@21` on macOS).

```sh
./gradlew test
./gradlew bootRun            # http://localhost:8080/fhir
```

or with Docker: `docker build -t mychart-danish-proxy . && docker run -p 8080:8080 mychart-danish-proxy`.

### Trying it with your own session

Log in to minsundhedsplatform.dk in Chrome, open DevTools → Network, then:

```sh
./scripts/smoke-test.sh          # optional port argument, default 8080
```

The script asks you to copy the `Cookie` and the `__RequestVerificationToken` **request header**
values of a `/MyChartPPR1/api/...` request and reads them from the clipboard. Long cookies are
therefore not mangled by the terminal and never end up in shell history or on screen. It prints
only status codes and result counts, no health data. Log out afterwards.

## Authentication and safety

The proxy is stateless and has no login of its own: send the MyChart session with every
request. Forwarded by default: `cookie`, `__requestverificationtoken`, `user-agent`,
`accept-language`, `referer` (`mychart.client.forwarded-headers`). Every `/api/...` call needs
the `__RequestVerificationToken` header; without it MyChart redirects to its login page. A
redirect or an HTML login page is returned as **401**, a refused call as **403**, other upstream
failures as **502**.

MyChart uses POST for reads, so read-only cannot be enforced on the HTTP verb. `MyChartClient`
instead only reaches a fixed allowlist of read endpoints (`MyChartClient.READ_ONLY_ENDPOINTS`).
Scheduling (`ReserveAppointment`, `DeleteReservationFromSlot`, `DecisionTree/NextStep`),
messaging and the portal's usage/audit loggers are not reachable.

Detail calls (per result group, visit, referral, flowsheet) are capped by
`mychart.client.max-detail-calls` (default 40). A failing detail call degrades to list-level data;
401/403 always fail the request.

## Mapping notes and known gaps

* **No CPR.** MyChart exposes none, so subjects use dhroxy's "unknown patient" reference
  (data-absent-reason) and Patient has no identifier. A CPR identifier search returns nothing.
* **Labs:** one Observation per result component, id `lab-{orderKey}-{componentId}`. Only a value
  that is itself numeric (`8,4` → 8.4) becomes a Quantity; `<5` or `Negativ` stay strings. The
  abnormal flag is dropped, as in dhroxy's LabMapper. The default window is the last 6 months,
  as in dhroxy. `category` (område) is matched on MyChart's `resultType`.
* **Dates:** ISO fields are used as-is; display dates (`dd-MM-yyyy`, `dd.MM.yyyy`, `12. okt. 2024`)
  are read in `Europe/Copenhagen`; anything else is left out rather than guessed.
* **Organization ids:** numeric MyChart ids are kept; others get a stable hash, because the shared
  DTO uses an integer id.
* **Request bodies were derived from a capture of field types, not documented values.** Still to
  confirm against more accounts: `GetList.groupType` (configurable, default `"0"`), whether an
  empty `PageNonce` is accepted, the `contextID`/`contextINI` pair for `LoadReportContent`, and the
  keys used for `GetInpatientDetailsByCSN`.
* Encounters and appointments come from `FetchH2GHeader`, which may return only recent visits;
  the paged `Visits/VisitsList/LoadPast` would give full history.

## Configuration

```yaml
mychart:
  client:
    fhir-path: "/fhir"
    base-url: "https://www.minsundhedsplatform.dk"
    instance-path: "/MyChartPPR1"
    max-detail-calls: 40
    test-results-group-type: "0"
    test-results-max-results: 100
    rewrite-namespaces: true
```

## License

Apache License 2.0. Contains code from dhroxy (Apache 2.0); see [VENDORED.md](VENDORED.md).
