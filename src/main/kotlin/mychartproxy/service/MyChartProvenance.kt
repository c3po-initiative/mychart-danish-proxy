package mychartproxy.service

import ca.uhn.fhir.context.FhirContext
import mychartproxy.config.MyChartClientProperties
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier

/**
 * Marks mapper output as originating from Sundhedsplatformen.
 *
 * The shared mappers stamp local identifier/code systems under `https://www.sundhed.dk/`.
 * For MyChart data those values (CSNs, component ids, referral ids) are issued by
 * Sundhedsplatformen, so the namespace is moved to [MyChartClientProperties.sourceNamespace].
 * Standard systems (CPR, CVR, HL7 terminology, UCUM) are untouched, as is everything else
 * about the resources. `meta.source` records the upstream instance.
 */
class MyChartProvenance(fhirContext: FhirContext, private val props: MyChartClientProperties) {
    private val terser = fhirContext.newTerser()
    private val source = props.baseUrl.trimEnd('/') + "/" + props.instancePath.trim('/')

    fun apply(bundle: Bundle): Bundle {
        bundle.entry.mapNotNull { it.resource }.forEach { resource ->
            resource.meta.source = source
            if (props.rewriteNamespaces) {
                terser.getAllPopulatedChildElementsOfType(resource, Identifier::class.java).forEach {
                    it.system = rewrite(it.system)
                }
                terser.getAllPopulatedChildElementsOfType(resource, Coding::class.java).forEach {
                    it.system = rewrite(it.system)
                }
            }
        }
        return bundle
    }

    internal fun rewrite(system: String?): String? =
        if (system != null && system.startsWith(SUNDHED_NAMESPACE)) props.sourceNamespace + system.removePrefix(SUNDHED_NAMESPACE)
        else system

    companion object {
        const val SUNDHED_NAMESPACE = "https://www.sundhed.dk/"
    }
}
