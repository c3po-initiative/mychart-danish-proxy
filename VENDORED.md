# Vendored code from dhroxy

`src/main/kotlin/mychartproxy/dhroxy/` contains dhroxy's FHIR mappers and sundhed.dk DTOs,
copied **unchanged except for the package name** so that this proxy produces the same FHIR
resources as dhroxy.

* Source: https://github.com/c3po-initiative/dhroxy
* Commit: `afe885c` (main, 2026-10-07)
* License: Apache License 2.0 (see `LICENSE`)

| Here | dhroxy |
|---|---|
| `mychartproxy.dhroxy.mapper.*` | `dhroxy.mapper.*` (DanishFhir, Patient-, Lab-, HomeMeasurement-, Condition-, Encounter-, Appointment-, DocumentReference-, Referral-, Organization-, ImagingMapper) |
| `mychartproxy.dhroxy.model.*` | `dhroxy.model.*` (the DTOs those mappers read) |
| `src/test/resources/dhroxy-baseline/sundhed-fixtures.json` | `src/test/resources/sundhed-stub/fixtures.json` (synthetic data, used by the parity test) |

## Keeping in sync

Do not edit these files to fit MyChart; adapt in `mychartproxy.adapter` instead. To pick up a
dhroxy mapper change, copy the files again, rewrite the package
(`dhroxy.mapper` → `mychartproxy.dhroxy.mapper`, `dhroxy.model` → `mychartproxy.dhroxy.model`),
update the commit above and run `./gradlew test`.
