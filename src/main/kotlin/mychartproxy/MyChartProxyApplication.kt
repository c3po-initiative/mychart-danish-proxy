package mychartproxy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MyChartProxyApplication

fun main(args: Array<String>) {
    runApplication<MyChartProxyApplication>(*args)
}
