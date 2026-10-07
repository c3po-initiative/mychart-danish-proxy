package mychartproxy.config

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import ca.uhn.fhir.rest.server.RestfulServer
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor
import mychartproxy.controller.MyChartResourceProvider
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * FHIR endpoint (default `/fhir`) backed by Sundhedsplatformen (MyChart).
 */
@Configuration
class MyChartFhirServerConfig(
    private val fhirContext: FhirContext,
    private val providers: List<MyChartResourceProvider>,
    private val props: MyChartClientProperties
) {
    @Bean
    fun myChartFhirServlet(): ServletRegistrationBean<RestfulServer> {
        val server = object : RestfulServer(fhirContext) {
            override fun initialize() {
                super.initialize()
                serverName = "mychart-danish-proxy (Sundhedsplatformen / Epic MyChart)"
                registerProviders(providers)
                registerInterceptor(ResponseHighlighterInterceptor())
                setDefaultResponseEncoding(EncodingEnum.JSON)
                isDefaultPrettyPrint = true
            }
        }
        val path = "/" + props.fhirPath.trim('/')
        return ServletRegistrationBean<RestfulServer>(server, "$path/*").apply {
            setName("myChartFhirServlet")
            setLoadOnStartup(1)
        }
    }
}
