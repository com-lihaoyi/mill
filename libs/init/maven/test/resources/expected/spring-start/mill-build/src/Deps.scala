package millbuild
import mill.javalib.*
object Deps {

  val activemqBom = mvn"org.apache.activemq:activemq-bom:6.1.7"
  val artemisBom = mvn"org.apache.activemq:artemis-bom:2.40.0"
  val assertjBom = mvn"org.assertj:assertj-bom:3.27.4"
  val braveBom = mvn"io.zipkin.brave:brave-bom:6.1.0"
  val groovyBom = mvn"org.apache.groovy:groovy-bom:4.0.28"
  val infinispanBom = mvn"org.infinispan:infinispan-bom:15.2.6.Final"
  val jacksonBom = mvn"com.fasterxml.jackson:jackson-bom:2.19.2"
  val javaDriverBom = mvn"org.apache.cassandra:java-driver-bom:4.19.0"
  val jaxbBom = mvn"org.glassfish.jaxb:jaxb-bom:4.0.5"
  val jerseyBom = mvn"org.glassfish.jersey:jersey-bom:3.1.11"
  val jettyBom = mvn"org.eclipse.jetty:jetty-bom:12.0.27"
  val jettyEe10Bom = mvn"org.eclipse.jetty.ee10:jetty-ee10-bom:12.0.27"
  val junitBom = mvn"org.junit:junit-bom:5.12.2"
  val kotlinBom = mvn"org.jetbrains.kotlin:kotlin-bom:1.9.25"
  val kotlinxCoroutinesBom =
    mvn"org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1"
  val kotlinxSerializationBom =
    mvn"org.jetbrains.kotlinx:kotlinx-serialization-bom:1.6.3"
  val log4jBom = mvn"org.apache.logging.log4j:log4j-bom:2.24.3"
  val micrometerBom = mvn"io.micrometer:micrometer-bom:1.15.4"
  val micrometerTracingBom = mvn"io.micrometer:micrometer-tracing-bom:1.5.4"
  val mockitoBom = mvn"org.mockito:mockito-bom:5.17.0"
  val mongodbDriverBom = mvn"org.mongodb:mongodb-driver-bom:5.5.1"
  val nettyBom = mvn"io.netty:netty-bom:4.1.127.Final"
  val opentelemetryBom = mvn"io.opentelemetry:opentelemetry-bom:1.49.0"
  val prometheusMetricsBom = mvn"io.prometheus:prometheus-metrics-bom:1.3.10"
  val pulsarBom = mvn"org.apache.pulsar:pulsar-bom:4.0.6"
  val pulsarClientReactiveBom =
    mvn"org.apache.pulsar:pulsar-client-reactive-bom:0.6.0"
  val querydslBom = mvn"com.querydsl:querydsl-bom:5.1.0"
  val reactorBom = mvn"io.projectreactor:reactor-bom:2024.0.10"
  val restAssuredBom = mvn"io.rest-assured:rest-assured-bom:5.5.6"
  val rsocketBom = mvn"io.rsocket:rsocket-bom:1.1.5"
  val seleniumBom = mvn"org.seleniumhq.selenium:selenium-bom:4.31.0"
  val simpleclient_bom = mvn"io.prometheus:simpleclient_bom:0.16.0"
  val springAmqpBom = mvn"org.springframework.amqp:spring-amqp-bom:3.2.7"
  val springBatchBom = mvn"org.springframework.batch:spring-batch-bom:5.2.3"
  val springBootStarterDataJdbc =
    mvn"org.springframework.boot:spring-boot-starter-data-jdbc:3.5.6"
  val springDataBom = mvn"org.springframework.data:spring-data-bom:2025.0.4"
  val springFrameworkBom = mvn"org.springframework:spring-framework-bom:6.2.11"
  val springIntegrationBom =
    mvn"org.springframework.integration:spring-integration-bom:6.5.2"
  val springPulsarBom = mvn"org.springframework.pulsar:spring-pulsar-bom:1.2.10"
  val springRestdocsBom =
    mvn"org.springframework.restdocs:spring-restdocs-bom:3.0.5"
  val springSecurityBom =
    mvn"org.springframework.security:spring-security-bom:6.5.5"
  val springSessionBom =
    mvn"org.springframework.session:spring-session-bom:3.5.2"
  val springWsBom = mvn"org.springframework.ws:spring-ws-bom:4.1.1"
  val testcontainersBom = mvn"org.testcontainers:testcontainers-bom:1.21.3"
  val zipkinReporterBom = mvn"io.zipkin.reporter2:zipkin-reporter-bom:3.5.1"
}
