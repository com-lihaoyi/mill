package millbuild
import mill.javalib.*
object Deps {

  val HikariCP = mvn"com.zaxxer:HikariCP:6.3.3"
  val `ehcache#0` = mvn"org.ehcache:ehcache:3.10.9"
  val `ehcache#1` = mvn"org.ehcache:ehcache:3.10.9;classifier=jakarta"
  val `ehcacheTransactions#0` = mvn"org.ehcache:ehcache-transactions:3.10.9"
  val `ehcacheTransactions#1` =
    mvn"org.ehcache:ehcache-transactions:3.10.9;classifier=jakarta"
  val `kafkaClients#0` = mvn"org.apache.kafka:kafka-clients:3.9.1"
  val `kafkaClients#1` =
    mvn"org.apache.kafka:kafka-clients:3.9.1;classifier=test"
  val `kafkaServerCommon#0` = mvn"org.apache.kafka:kafka-server-common:3.9.1"
  val `kafkaServerCommon#1` =
    mvn"org.apache.kafka:kafka-server-common:3.9.1;classifier=test"
  val `kafka_212#0` = mvn"org.apache.kafka:kafka_2.12:3.9.1"
  val `kafka_212#1` = mvn"org.apache.kafka:kafka_2.12:3.9.1;classifier=test"
  val `kafka_213#0` = mvn"org.apache.kafka:kafka_2.13:3.9.1"
  val `kafka_213#1` = mvn"org.apache.kafka:kafka_2.13:3.9.1;classifier=test"
  val activemqBom = mvn"org.apache.activemq:activemq-bom:6.1.7"
  val activemqConsole =
    mvn"org.apache.activemq:activemq-console:6.1.7;exclude=commons-logging:commons-logging"
  val activemqSpring =
    mvn"org.apache.activemq:activemq-spring:6.1.7;exclude=commons-logging:commons-logging"
  val amqpClient = mvn"com.rabbitmq:amqp-client:5.25.0"
  val angusCore = mvn"org.eclipse.angus:angus-core:2.0.4"
  val angusMail = mvn"org.eclipse.angus:angus-mail:2.0.4"
  val artemisBom = mvn"org.apache.activemq:artemis-bom:2.40.0"
  val aspectjrt = mvn"org.aspectj:aspectjrt:1.9.24"
  val aspectjtools = mvn"org.aspectj:aspectjtools:1.9.24"
  val aspectjweaver = mvn"org.aspectj:aspectjweaver:1.9.24"
  val assertjBom = mvn"org.assertj:assertj-bom:3.27.4"
  val awaitility = mvn"org.awaitility:awaitility:4.2.2"
  val awaitilityGroovy = mvn"org.awaitility:awaitility-groovy:4.2.2"
  val awaitilityKotlin = mvn"org.awaitility:awaitility-kotlin:4.2.2"
  val awaitilityScala = mvn"org.awaitility:awaitility-scala:4.2.2"
  val braveBom = mvn"io.zipkin.brave:brave-bom:6.1.0"
  val byteBuddy = mvn"net.bytebuddy:byte-buddy:1.17.7"
  val byteBuddyAgent = mvn"net.bytebuddy:byte-buddy-agent:1.17.7"
  val cache2kApi = mvn"org.cache2k:cache2k-api:2.6.1.Final"
  val cache2kConfig = mvn"org.cache2k:cache2k-config:2.6.1.Final"
  val cache2kCore = mvn"org.cache2k:cache2k-core:2.6.1.Final"
  val cache2kJcache = mvn"org.cache2k:cache2k-jcache:2.6.1.Final"
  val cache2kMicrometer = mvn"org.cache2k:cache2k-micrometer:2.6.1.Final"
  val cache2kSpring = mvn"org.cache2k:cache2k-spring:2.6.1.Final"
  val cacheApi = mvn"javax.cache:cache-api:1.1.1"
  val caffeine = mvn"com.github.ben-manes.caffeine:caffeine:3.2.2"
  val classmate = mvn"com.fasterxml:classmate:1.7.0"
  val commonsCodec = mvn"commons-codec:commons-codec:1.18.0"
  val commonsCompiler = mvn"org.codehaus.janino:commons-compiler:3.1.12"
  val commonsCompilerJdk = mvn"org.codehaus.janino:commons-compiler-jdk:3.1.12"
  val commonsDbcp2 =
    mvn"org.apache.commons:commons-dbcp2:2.13.0;exclude=commons-logging:commons-logging"
  val commonsLang3 = mvn"org.apache.commons:commons-lang3:3.17.0"
  val commonsPool = mvn"commons-pool:commons-pool:1.6"
  val commonsPool2 = mvn"org.apache.commons:commons-pool2:2.12.1"
  val connect = mvn"org.apache.kafka:connect:3.9.1"
  val connectApi = mvn"org.apache.kafka:connect-api:3.9.1"
  val connectBasicAuthExtension =
    mvn"org.apache.kafka:connect-basic-auth-extension:3.9.1"
  val connectFile = mvn"org.apache.kafka:connect-file:3.9.1"
  val connectJson = mvn"org.apache.kafka:connect-json:3.9.1"
  val connectMirror = mvn"org.apache.kafka:connect-mirror:3.9.1"
  val connectMirrorClient = mvn"org.apache.kafka:connect-mirror-client:3.9.1"
  val connectRuntime = mvn"org.apache.kafka:connect-runtime:3.9.1"
  val connectTransforms = mvn"org.apache.kafka:connect-transforms:3.9.1"
  val crac = mvn"org.crac:crac:1.5.0"
  val dependencyManagementPlugin =
    mvn"io.spring.gradle:dependency-management-plugin:1.1.7"
  val derby = mvn"org.apache.derby:derby:10.16.1.1"
  val derbyclient = mvn"org.apache.derby:derbyclient:10.16.1.1"
  val derbynet = mvn"org.apache.derby:derbynet:10.16.1.1"
  val derbyoptionaltools = mvn"org.apache.derby:derbyoptionaltools:10.16.1.1"
  val derbyshared = mvn"org.apache.derby:derbyshared:10.16.1.1"
  val derbytools = mvn"org.apache.derby:derbytools:10.16.1.1"
  val dsn = mvn"org.eclipse.angus:dsn:2.0.4"
  val ehcacheClustered = mvn"org.ehcache:ehcache-clustered:3.10.9"
  val elasticsearchJava = mvn"co.elastic.clients:elasticsearch-java:8.18.6"
  val elasticsearchRestClient =
    mvn"org.elasticsearch.client:elasticsearch-rest-client:8.18.6;exclude=commons-logging:commons-logging"
  val elasticsearchRestClientSniffer =
    mvn"org.elasticsearch.client:elasticsearch-rest-client-sniffer:8.18.6;exclude=commons-logging:commons-logging"
  val flywayCommandline = mvn"org.flywaydb:flyway-commandline:11.7.2"
  val flywayCore = mvn"org.flywaydb:flyway-core:11.7.2"
  val flywayDatabaseCassandra =
    mvn"org.flywaydb:flyway-database-cassandra:11.7.2"
  val flywayDatabaseDb2 = mvn"org.flywaydb:flyway-database-db2:11.7.2"
  val flywayDatabaseDerby = mvn"org.flywaydb:flyway-database-derby:11.7.2"
  val flywayDatabaseHsqldb = mvn"org.flywaydb:flyway-database-hsqldb:11.7.2"
  val flywayDatabaseInformix = mvn"org.flywaydb:flyway-database-informix:11.7.2"
  val flywayDatabaseMongodb = mvn"org.flywaydb:flyway-database-mongodb:11.7.2"
  val flywayDatabaseOracle = mvn"org.flywaydb:flyway-database-oracle:11.7.2"
  val flywayDatabasePostgresql =
    mvn"org.flywaydb:flyway-database-postgresql:11.7.2"
  val flywayDatabaseRedshift = mvn"org.flywaydb:flyway-database-redshift:11.7.2"
  val flywayDatabaseSaphana = mvn"org.flywaydb:flyway-database-saphana:11.7.2"
  val flywayDatabaseSnowflake =
    mvn"org.flywaydb:flyway-database-snowflake:11.7.2"
  val flywayDatabaseSybasease =
    mvn"org.flywaydb:flyway-database-sybasease:11.7.2"
  val flywayFirebird = mvn"org.flywaydb:flyway-firebird:11.7.2"
  val flywayGcpBigquery = mvn"org.flywaydb:flyway-gcp-bigquery:11.7.2"
  val flywayGcpSpanner = mvn"org.flywaydb:flyway-gcp-spanner:11.7.2"
  val flywayMysql = mvn"org.flywaydb:flyway-mysql:11.7.2"
  val flywaySinglestore = mvn"org.flywaydb:flyway-singlestore:11.7.2"
  val flywaySqlserver = mvn"org.flywaydb:flyway-sqlserver:11.7.2"
  val freemarker = mvn"org.freemarker:freemarker:2.3.34"
  val generator = mvn"org.apache.kafka:generator:3.9.1"
  val gimap = mvn"org.eclipse.angus:gimap:2.0.4"
  val graphqlJava = mvn"com.graphql-java:graphql-java:24.1"
  val groovyBom = mvn"org.apache.groovy:groovy-bom:4.0.28"
  val gson = mvn"com.google.code.gson:gson:2.13.2"
  val guava = mvn"com.github.ben-manes.caffeine:guava:3.2.2"
  val h2 = mvn"com.h2database:h2:2.3.232"
  val hamcrest = mvn"org.hamcrest:hamcrest:3.0"
  val hamcrestCore = mvn"org.hamcrest:hamcrest-core:3.0"
  val hamcrestLibrary = mvn"org.hamcrest:hamcrest-library:3.0"
  val hazelcast = mvn"com.hazelcast:hazelcast:5.5.0"
  val hazelcastSpring = mvn"com.hazelcast:hazelcast-spring:5.5.0"
  val hibernateAgroal = mvn"org.hibernate.orm:hibernate-agroal:6.6.29.Final"
  val hibernateAnt = mvn"org.hibernate.orm:hibernate-ant:6.6.29.Final"
  val hibernateC3p0 = mvn"org.hibernate.orm:hibernate-c3p0:6.6.29.Final"
  val hibernateCommunityDialects =
    mvn"org.hibernate.orm:hibernate-community-dialects:6.6.29.Final"
  val hibernateCore = mvn"org.hibernate.orm:hibernate-core:6.6.29.Final"
  val hibernateEnvers = mvn"org.hibernate.orm:hibernate-envers:6.6.29.Final"
  val hibernateGraalvm = mvn"org.hibernate.orm:hibernate-graalvm:6.6.29.Final"
  val hibernateHikaricp = mvn"org.hibernate.orm:hibernate-hikaricp:6.6.29.Final"
  val hibernateJcache = mvn"org.hibernate.orm:hibernate-jcache:6.6.29.Final"
  val hibernateJpamodelgen =
    mvn"org.hibernate.orm:hibernate-jpamodelgen:6.6.29.Final"
  val hibernateMicrometer =
    mvn"org.hibernate.orm:hibernate-micrometer:6.6.29.Final"
  val hibernateProxool = mvn"org.hibernate.orm:hibernate-proxool:6.6.29.Final"
  val hibernateSpatial = mvn"org.hibernate.orm:hibernate-spatial:6.6.29.Final"
  val hibernateTesting = mvn"org.hibernate.orm:hibernate-testing:6.6.29.Final"
  val hibernateValidator =
    mvn"org.hibernate.validator:hibernate-validator:8.0.3.Final"
  val hibernateValidatorAnnotationProcessor =
    mvn"org.hibernate.validator:hibernate-validator-annotation-processor:8.0.3.Final"
  val hibernateVibur = mvn"org.hibernate.orm:hibernate-vibur:6.6.29.Final"
  val hsqldb = mvn"org.hsqldb:hsqldb:2.7.3"
  val htmlunit =
    mvn"org.htmlunit:htmlunit:4.11.1;exclude=commons-logging:commons-logging"
  val htmlunit3Driver = mvn"org.seleniumhq.selenium:htmlunit3-driver:4.30.0"
  val httpasyncclient =
    mvn"org.apache.httpcomponents:httpasyncclient:4.1.5;exclude=commons-logging:commons-logging"
  val httpclient5 = mvn"org.apache.httpcomponents.client5:httpclient5:5.5"
  val httpclient5Cache =
    mvn"org.apache.httpcomponents.client5:httpclient5-cache:5.5"
  val httpclient5Fluent =
    mvn"org.apache.httpcomponents.client5:httpclient5-fluent:5.5"
  val httpcore = mvn"org.apache.httpcomponents:httpcore:4.4.16"
  val httpcore5 = mvn"org.apache.httpcomponents.core5:httpcore5:5.3.5"
  val httpcore5H2 = mvn"org.apache.httpcomponents.core5:httpcore5-h2:5.3.5"
  val httpcore5Reactive =
    mvn"org.apache.httpcomponents.core5:httpcore5-reactive:5.3.5"
  val httpcoreNio = mvn"org.apache.httpcomponents:httpcore-nio:4.4.16"
  val imap = mvn"org.eclipse.angus:imap:2.0.4"
  val infinispanBom = mvn"org.infinispan:infinispan-bom:15.2.6.Final"
  val influxdbJava = mvn"org.influxdb:influxdb-java:2.25"
  val jacksonBom = mvn"com.fasterxml.jackson:jackson-bom:2.19.2"
  val jakartaActivationApi =
    mvn"jakarta.activation:jakarta.activation-api:2.1.4"
  val jakartaAnnotationApi =
    mvn"jakarta.annotation:jakarta.annotation-api:2.1.1"
  val jakartaInjectApi = mvn"jakarta.inject:jakarta.inject-api:2.0.1"
  val jakartaJmsApi = mvn"jakarta.jms:jakarta.jms-api:3.1.0"
  val jakartaJsonApi = mvn"jakarta.json:jakarta.json-api:2.1.3"
  val jakartaJsonBindApi = mvn"jakarta.json.bind:jakarta.json.bind-api:3.0.1"
  val jakartaMail = mvn"org.eclipse.angus:jakarta.mail:2.0.4"
  val jakartaMailApi = mvn"jakarta.mail:jakarta.mail-api:2.1.4"
  val jakartaManagementJ2eeApi =
    mvn"jakarta.management.j2ee:jakarta.management.j2ee-api:1.1.4"
  val jakartaPersistenceApi =
    mvn"jakarta.persistence:jakarta.persistence-api:3.1.0"
  val jakartaServletApi = mvn"jakarta.servlet:jakarta.servlet-api:6.0.0"
  val jakartaServletJspJstl =
    mvn"org.glassfish.web:jakarta.servlet.jsp.jstl:3.0.1"
  val jakartaServletJspJstlApi =
    mvn"jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:3.0.2"
  val jakartaTransactionApi =
    mvn"jakarta.transaction:jakarta.transaction-api:2.0.1"
  val jakartaValidationApi =
    mvn"jakarta.validation:jakarta.validation-api:3.0.2"
  val jakartaWebsocketApi = mvn"jakarta.websocket:jakarta.websocket-api:2.1.1"
  val jakartaWebsocketClientApi =
    mvn"jakarta.websocket:jakarta.websocket-client-api:2.1.1"
  val jakartaWsRsApi = mvn"jakarta.ws.rs:jakarta.ws.rs-api:3.1.0"
  val jakartaXmlBindApi = mvn"jakarta.xml.bind:jakarta.xml.bind-api:4.0.2"
  val jakartaXmlSoapApi = mvn"jakarta.xml.soap:jakarta.xml.soap-api:3.0.2"
  val jakartaXmlWsApi = mvn"jakarta.xml.ws:jakarta.xml.ws-api:4.0.2"
  val janino = mvn"org.codehaus.janino:janino:3.1.12"
  val javaClient = mvn"com.couchbase.client:java-client:3.8.3"
  val javaDriverBom = mvn"org.apache.cassandra:java-driver-bom:4.19.0"
  val javaDriverCore = mvn"org.apache.cassandra:java-driver-core:4.19.0"
  val jaxbBom = mvn"org.glassfish.jaxb:jaxb-bom:4.0.5"
  val jaxen = mvn"jaxen:jaxen:2.0.0"
  val jaybird = mvn"org.firebirdsql.jdbc:jaybird:6.0.3"
  val jbossLogging = mvn"org.jboss.logging:jboss-logging:3.6.1.Final"
  val jcache = mvn"com.github.ben-manes.caffeine:jcache:3.2.2"
  val jcc = mvn"com.ibm.db2:jcc:12.1.2.0"
  val jclOverSlf4j = mvn"org.slf4j:jcl-over-slf4j:2.0.17"
  val jdom2 = mvn"org.jdom:jdom2:2.0.6.1"
  val jedis = mvn"redis.clients:jedis:6.0.0"
  val jerseyBom = mvn"org.glassfish.jersey:jersey-bom:3.1.11"
  val jettyBom = mvn"org.eclipse.jetty:jetty-bom:12.0.27"
  val jettyEe10Bom = mvn"org.eclipse.jetty.ee10:jetty-ee10-bom:12.0.27"
  val jettyReactiveHttpclient =
    mvn"org.eclipse.jetty:jetty-reactive-httpclient:4.0.11"
  val jmustache = mvn"com.samskivert:jmustache:1.16"
  val jooq = mvn"org.jooq:jooq:3.19.26"
  val jooqCodegen = mvn"org.jooq:jooq-codegen:3.19.26"
  val jooqKotlin = mvn"org.jooq:jooq-kotlin:3.19.26"
  val jooqMeta = mvn"org.jooq:jooq-meta:3.19.26"
  val jsonPath = mvn"com.jayway.jsonpath:json-path:2.9.0"
  val jsonPathAssert = mvn"com.jayway.jsonpath:json-path-assert:2.9.0"
  val jsonSmart = mvn"net.minidev:json-smart:2.5.2"
  val jsonassert = mvn"org.skyscreamer:jsonassert:1.5.3"
  val jtds = mvn"net.sourceforge.jtds:jtds:1.3.1"
  val julToSlf4j = mvn"org.slf4j:jul-to-slf4j:2.0.17"
  val junit = mvn"junit:junit:4.13.2"
  val junitBom = mvn"org.junit:junit-bom:5.12.2"
  val kafkaLog4jAppender = mvn"org.apache.kafka:kafka-log4j-appender:3.9.1"
  val kafkaMetadata = mvn"org.apache.kafka:kafka-metadata:3.9.1"
  val kafkaRaft = mvn"org.apache.kafka:kafka-raft:3.9.1"
  val kafkaServer = mvn"org.apache.kafka:kafka-server:3.9.1"
  val kafkaShell = mvn"org.apache.kafka:kafka-shell:3.9.1"
  val kafkaStorage = mvn"org.apache.kafka:kafka-storage:3.9.1"
  val kafkaStorageApi = mvn"org.apache.kafka:kafka-storage-api:3.9.1"
  val kafkaStreams = mvn"org.apache.kafka:kafka-streams:3.9.1"
  val kafkaStreamsScala_212 =
    mvn"org.apache.kafka:kafka-streams-scala_2.12:3.9.1"
  val kafkaStreamsScala_213 =
    mvn"org.apache.kafka:kafka-streams-scala_2.13:3.9.1"
  val kafkaStreamsTestUtils =
    mvn"org.apache.kafka:kafka-streams-test-utils:3.9.1"
  val kafkaTools = mvn"org.apache.kafka:kafka-tools:3.9.1"
  val kotlinBom = mvn"org.jetbrains.kotlin:kotlin-bom:1.9.25"
  val kotlinxCoroutinesBom =
    mvn"org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1"
  val kotlinxSerializationBom =
    mvn"org.jetbrains.kotlinx:kotlinx-serialization-bom:1.6.3"
  val lettuceCore = mvn"io.lettuce:lettuce-core:6.6.0.RELEASE"
  val liquibaseCdi = mvn"org.liquibase:liquibase-cdi:4.31.1"
  val liquibaseCore = mvn"org.liquibase:liquibase-core:4.31.1"
  val log4jBom = mvn"org.apache.logging.log4j:log4j-bom:2.24.3"
  val log4jOverSlf4j = mvn"org.slf4j:log4j-over-slf4j:2.0.17"
  val logbackClassic = mvn"ch.qos.logback:logback-classic:1.5.18"
  val logbackCore = mvn"ch.qos.logback:logback-core:1.5.18"
  val loggingMailhandler = mvn"org.eclipse.angus:logging-mailhandler:2.0.4"
  val lombok = mvn"org.projectlombok:lombok:1.18.40"
  val mariadbJavaClient = mvn"org.mariadb.jdbc:mariadb-java-client:3.5.6"
  val micrometerBom = mvn"io.micrometer:micrometer-bom:1.15.4"
  val micrometerRegistryStackdriver =
    mvn"io.micrometer:micrometer-registry-stackdriver:1.15.4;exclude=javax.annotation:javax.annotation-api"
  val micrometerTracingBom = mvn"io.micrometer:micrometer-tracing-bom:1.5.4"
  val mockitoBom = mvn"org.mockito:mockito-bom:5.17.0"
  val moneyApi = mvn"javax.money:money-api:1.1"
  val mongodbDriverBom = mvn"org.mongodb:mongodb-driver-bom:5.5.1"
  val mssqlJdbc = mvn"com.microsoft.sqlserver:mssql-jdbc:12.10.1.jre11"
  val mysqlConnectorJ =
    mvn"com.mysql:mysql-connector-j:9.4.0;exclude=com.google.protobuf:protobuf-java"
  val nekohtml = mvn"net.sourceforge.nekohtml:nekohtml:1.9.22"
  val neo4jJavaDriver = mvn"org.neo4j.driver:neo4j-java-driver:5.28.9"
  val nettyBom = mvn"io.netty:netty-bom:4.1.127.Final"
  val ojdbc11 = mvn"com.oracle.database.jdbc:ojdbc11:23.7.0.25.01"
  val ojdbc11Production =
    mvn"com.oracle.database.jdbc:ojdbc11-production:23.7.0.25.01"
  val ojdbc17 = mvn"com.oracle.database.jdbc:ojdbc17:23.7.0.25.01"
  val ojdbc17Production =
    mvn"com.oracle.database.jdbc:ojdbc17-production:23.7.0.25.01"
  val ojdbc8 = mvn"com.oracle.database.jdbc:ojdbc8:23.7.0.25.01"
  val ojdbc8Production =
    mvn"com.oracle.database.jdbc:ojdbc8-production:23.7.0.25.01"
  val ons = mvn"com.oracle.database.ha:ons:23.7.0.25.01"
  val opentelemetryBom = mvn"io.opentelemetry:opentelemetry-bom:1.49.0"
  val oracleR2dbc = mvn"com.oracle.database.r2dbc:oracle-r2dbc:1.3.0"
  val oraclepki = mvn"com.oracle.database.security:oraclepki:23.7.0.25.01"
  val orai18n = mvn"com.oracle.database.nls:orai18n:23.7.0.25.01"
  val pooledJms = mvn"org.messaginghub:pooled-jms:3.1.7"
  val pop3 = mvn"org.eclipse.angus:pop3:2.0.4"
  val postgresql = mvn"org.postgresql:postgresql:42.7.7"
  val prometheusMetricsBom = mvn"io.prometheus:prometheus-metrics-bom:1.3.10"
  val pulsarBom = mvn"org.apache.pulsar:pulsar-bom:4.0.6"
  val pulsarClientReactiveBom =
    mvn"org.apache.pulsar:pulsar-client-reactive-bom:0.6.0"
  val quartz = mvn"org.quartz-scheduler:quartz:2.5.0"
  val quartzJobs = mvn"org.quartz-scheduler:quartz-jobs:2.5.0"
  val querydslBom = mvn"com.querydsl:querydsl-bom:5.1.0"
  val r2dbcH2 = mvn"io.r2dbc:r2dbc-h2:1.0.0.RELEASE"
  val r2dbcMariadb = mvn"org.mariadb:r2dbc-mariadb:1.3.0"
  val r2dbcMssql = mvn"io.r2dbc:r2dbc-mssql:1.0.3.RELEASE"
  val r2dbcMysql = mvn"io.asyncer:r2dbc-mysql:1.4.1"
  val r2dbcPool = mvn"io.r2dbc:r2dbc-pool:1.0.2.RELEASE"
  val r2dbcPostgresql = mvn"org.postgresql:r2dbc-postgresql:1.0.7.RELEASE"
  val r2dbcProxy = mvn"io.r2dbc:r2dbc-proxy:1.1.6.RELEASE"
  val r2dbcSpi = mvn"io.r2dbc:r2dbc-spi:1.0.0.RELEASE"
  val reactiveStreams = mvn"org.reactivestreams:reactive-streams:1.0.4"
  val reactorBom = mvn"io.projectreactor:reactor-bom:2024.0.10"
  val restAssuredBom = mvn"io.rest-assured:rest-assured-bom:5.5.6"
  val rsi = mvn"com.oracle.database.jdbc:rsi:23.7.0.25.01"
  val rsocketBom = mvn"io.rsocket:rsocket-bom:1.1.5"
  val rxjava = mvn"io.reactivex.rxjava3:rxjava:3.1.11"
  val saajImpl = mvn"com.sun.xml.messaging.saaj:saaj-impl:3.0.4"
  val seleniumBom = mvn"org.seleniumhq.selenium:selenium-bom:4.31.0"
  val sendgridJava = mvn"com.sendgrid:sendgrid-java:4.10.3"
  val simpleclient_bom = mvn"io.prometheus:simpleclient_bom:0.16.0"
  val simplefan = mvn"com.oracle.database.ha:simplefan:23.7.0.25.01"
  val simulator = mvn"com.github.ben-manes.caffeine:simulator:3.2.2"
  val slf4jApi = mvn"org.slf4j:slf4j-api:2.0.17"
  val slf4jExt = mvn"org.slf4j:slf4j-ext:2.0.17"
  val slf4jJdk14 = mvn"org.slf4j:slf4j-jdk14:2.0.17"
  val slf4jJdkPlatformLogging = mvn"org.slf4j:slf4j-jdk-platform-logging:2.0.17"
  val slf4jLog4j12 = mvn"org.slf4j:slf4j-log4j12:2.0.17"
  val slf4jNop = mvn"org.slf4j:slf4j-nop:2.0.17"
  val slf4jReload4j = mvn"org.slf4j:slf4j-reload4j:2.0.17"
  val slf4jSimple = mvn"org.slf4j:slf4j-simple:2.0.17"
  val smtp = mvn"org.eclipse.angus:smtp:2.0.4"
  val snakeyaml = mvn"org.yaml:snakeyaml:2.4"
  val springAmqpBom = mvn"org.springframework.amqp:spring-amqp-bom:3.2.7"
  val springBatchBom = mvn"org.springframework.batch:spring-batch-bom:5.2.3"
  val springBoot = mvn"org.springframework.boot:spring-boot:3.5.6"
  val springBootActuator =
    mvn"org.springframework.boot:spring-boot-actuator:3.5.6"
  val springBootActuatorAutoconfigure =
    mvn"org.springframework.boot:spring-boot-actuator-autoconfigure:3.5.6"
  val springBootAutoconfigure =
    mvn"org.springframework.boot:spring-boot-autoconfigure:3.5.6"
  val springBootAutoconfigureProcessor =
    mvn"org.springframework.boot:spring-boot-autoconfigure-processor:3.5.6"
  val springBootBuildpackPlatform =
    mvn"org.springframework.boot:spring-boot-buildpack-platform:3.5.6"
  val springBootConfigurationMetadata =
    mvn"org.springframework.boot:spring-boot-configuration-metadata:3.5.6"
  val springBootConfigurationProcessor =
    mvn"org.springframework.boot:spring-boot-configuration-processor:3.5.6"
  val springBootDevtools =
    mvn"org.springframework.boot:spring-boot-devtools:3.5.6"
  val springBootDockerCompose =
    mvn"org.springframework.boot:spring-boot-docker-compose:3.5.6"
  val springBootJarmodeTools =
    mvn"org.springframework.boot:spring-boot-jarmode-tools:3.5.6"
  val springBootLoader = mvn"org.springframework.boot:spring-boot-loader:3.5.6"
  val springBootLoaderClassic =
    mvn"org.springframework.boot:spring-boot-loader-classic:3.5.6"
  val springBootLoaderTools =
    mvn"org.springframework.boot:spring-boot-loader-tools:3.5.6"
  val springBootPropertiesMigrator =
    mvn"org.springframework.boot:spring-boot-properties-migrator:3.5.6"
  val springBootStarter =
    mvn"org.springframework.boot:spring-boot-starter:3.5.6"
  val springBootStarterActivemq =
    mvn"org.springframework.boot:spring-boot-starter-activemq:3.5.6"
  val springBootStarterActuator =
    mvn"org.springframework.boot:spring-boot-starter-actuator:3.5.6"
  val springBootStarterAmqp =
    mvn"org.springframework.boot:spring-boot-starter-amqp:3.5.6"
  val springBootStarterAop =
    mvn"org.springframework.boot:spring-boot-starter-aop:3.5.6"
  val springBootStarterArtemis =
    mvn"org.springframework.boot:spring-boot-starter-artemis:3.5.6"
  val springBootStarterBatch =
    mvn"org.springframework.boot:spring-boot-starter-batch:3.5.6"
  val springBootStarterCache =
    mvn"org.springframework.boot:spring-boot-starter-cache:3.5.6"
  val springBootStarterDataCassandra =
    mvn"org.springframework.boot:spring-boot-starter-data-cassandra:3.5.6"
  val springBootStarterDataCassandraReactive =
    mvn"org.springframework.boot:spring-boot-starter-data-cassandra-reactive:3.5.6"
  val springBootStarterDataCouchbase =
    mvn"org.springframework.boot:spring-boot-starter-data-couchbase:3.5.6"
  val springBootStarterDataCouchbaseReactive =
    mvn"org.springframework.boot:spring-boot-starter-data-couchbase-reactive:3.5.6"
  val springBootStarterDataElasticsearch =
    mvn"org.springframework.boot:spring-boot-starter-data-elasticsearch:3.5.6"
  val springBootStarterDataJdbc =
    mvn"org.springframework.boot:spring-boot-starter-data-jdbc:3.5.6"
  val springBootStarterDataJpa =
    mvn"org.springframework.boot:spring-boot-starter-data-jpa:3.5.6"
  val springBootStarterDataLdap =
    mvn"org.springframework.boot:spring-boot-starter-data-ldap:3.5.6"
  val springBootStarterDataMongodb =
    mvn"org.springframework.boot:spring-boot-starter-data-mongodb:3.5.6"
  val springBootStarterDataMongodbReactive =
    mvn"org.springframework.boot:spring-boot-starter-data-mongodb-reactive:3.5.6"
  val springBootStarterDataNeo4j =
    mvn"org.springframework.boot:spring-boot-starter-data-neo4j:3.5.6"
  val springBootStarterDataR2dbc =
    mvn"org.springframework.boot:spring-boot-starter-data-r2dbc:3.5.6"
  val springBootStarterDataRedis =
    mvn"org.springframework.boot:spring-boot-starter-data-redis:3.5.6"
  val springBootStarterDataRedisReactive =
    mvn"org.springframework.boot:spring-boot-starter-data-redis-reactive:3.5.6"
  val springBootStarterDataRest =
    mvn"org.springframework.boot:spring-boot-starter-data-rest:3.5.6"
  val springBootStarterFreemarker =
    mvn"org.springframework.boot:spring-boot-starter-freemarker:3.5.6"
  val springBootStarterGraphql =
    mvn"org.springframework.boot:spring-boot-starter-graphql:3.5.6"
  val springBootStarterGroovyTemplates =
    mvn"org.springframework.boot:spring-boot-starter-groovy-templates:3.5.6"
  val springBootStarterHateoas =
    mvn"org.springframework.boot:spring-boot-starter-hateoas:3.5.6"
  val springBootStarterIntegration =
    mvn"org.springframework.boot:spring-boot-starter-integration:3.5.6"
  val springBootStarterJdbc =
    mvn"org.springframework.boot:spring-boot-starter-jdbc:3.5.6"
  val springBootStarterJersey =
    mvn"org.springframework.boot:spring-boot-starter-jersey:3.5.6"
  val springBootStarterJetty =
    mvn"org.springframework.boot:spring-boot-starter-jetty:3.5.6"
  val springBootStarterJooq =
    mvn"org.springframework.boot:spring-boot-starter-jooq:3.5.6"
  val springBootStarterJson =
    mvn"org.springframework.boot:spring-boot-starter-json:3.5.6"
  val springBootStarterLog4j2 =
    mvn"org.springframework.boot:spring-boot-starter-log4j2:3.5.6"
  val springBootStarterLogging =
    mvn"org.springframework.boot:spring-boot-starter-logging:3.5.6"
  val springBootStarterMail =
    mvn"org.springframework.boot:spring-boot-starter-mail:3.5.6"
  val springBootStarterMustache =
    mvn"org.springframework.boot:spring-boot-starter-mustache:3.5.6"
  val springBootStarterOauth2AuthorizationServer =
    mvn"org.springframework.boot:spring-boot-starter-oauth2-authorization-server:3.5.6"
  val springBootStarterOauth2Client =
    mvn"org.springframework.boot:spring-boot-starter-oauth2-client:3.5.6"
  val springBootStarterOauth2ResourceServer =
    mvn"org.springframework.boot:spring-boot-starter-oauth2-resource-server:3.5.6"
  val springBootStarterPulsar =
    mvn"org.springframework.boot:spring-boot-starter-pulsar:3.5.6"
  val springBootStarterPulsarReactive =
    mvn"org.springframework.boot:spring-boot-starter-pulsar-reactive:3.5.6"
  val springBootStarterQuartz =
    mvn"org.springframework.boot:spring-boot-starter-quartz:3.5.6"
  val springBootStarterReactorNetty =
    mvn"org.springframework.boot:spring-boot-starter-reactor-netty:3.5.6"
  val springBootStarterRsocket =
    mvn"org.springframework.boot:spring-boot-starter-rsocket:3.5.6"
  val springBootStarterSecurity =
    mvn"org.springframework.boot:spring-boot-starter-security:3.5.6"
  val springBootStarterTest =
    mvn"org.springframework.boot:spring-boot-starter-test:3.5.6"
  val springBootStarterThymeleaf =
    mvn"org.springframework.boot:spring-boot-starter-thymeleaf:3.5.6"
  val springBootStarterTomcat =
    mvn"org.springframework.boot:spring-boot-starter-tomcat:3.5.6"
  val springBootStarterUndertow =
    mvn"org.springframework.boot:spring-boot-starter-undertow:3.5.6"
  val springBootStarterValidation =
    mvn"org.springframework.boot:spring-boot-starter-validation:3.5.6"
  val springBootStarterWeb =
    mvn"org.springframework.boot:spring-boot-starter-web:3.5.6"
  val springBootStarterWebServices =
    mvn"org.springframework.boot:spring-boot-starter-web-services:3.5.6"
  val springBootStarterWebflux =
    mvn"org.springframework.boot:spring-boot-starter-webflux:3.5.6"
  val springBootStarterWebsocket =
    mvn"org.springframework.boot:spring-boot-starter-websocket:3.5.6"
  val springBootTest = mvn"org.springframework.boot:spring-boot-test:3.5.6"
  val springBootTestAutoconfigure =
    mvn"org.springframework.boot:spring-boot-test-autoconfigure:3.5.6"
  val springBootTestcontainers =
    mvn"org.springframework.boot:spring-boot-testcontainers:3.5.6"
  val springDataBom = mvn"org.springframework.data:spring-data-bom:2025.0.4"
  val springFrameworkBom = mvn"org.springframework:spring-framework-bom:6.2.11"
  val springGraphql = mvn"org.springframework.graphql:spring-graphql:1.4.2"
  val springGraphqlTest =
    mvn"org.springframework.graphql:spring-graphql-test:1.4.2"
  val springHateoas = mvn"org.springframework.hateoas:spring-hateoas:2.5.1"
  val springIntegrationBom =
    mvn"org.springframework.integration:spring-integration-bom:6.5.2"
  val springKafka = mvn"org.springframework.kafka:spring-kafka:3.3.10"
  val springKafkaTest = mvn"org.springframework.kafka:spring-kafka-test:3.3.10"
  val springLdapCore = mvn"org.springframework.ldap:spring-ldap-core:3.3.3"
  val springLdapLdifCore =
    mvn"org.springframework.ldap:spring-ldap-ldif-core:3.3.3"
  val springLdapOdm = mvn"org.springframework.ldap:spring-ldap-odm:3.3.3"
  val springLdapTest = mvn"org.springframework.ldap:spring-ldap-test:3.3.3"
  val springPulsarBom = mvn"org.springframework.pulsar:spring-pulsar-bom:1.2.10"
  val springRestdocsBom =
    mvn"org.springframework.restdocs:spring-restdocs-bom:3.0.5"
  val springRetry = mvn"org.springframework.retry:spring-retry:2.0.12"
  val springSecurityBom =
    mvn"org.springframework.security:spring-security-bom:6.5.5"
  val springSecurityOauth2AuthorizationServer =
    mvn"org.springframework.security:spring-security-oauth2-authorization-server:1.5.2"
  val springSessionBom =
    mvn"org.springframework.session:spring-session-bom:3.5.2"
  val springWsBom = mvn"org.springframework.ws:spring-ws-bom:4.1.1"
  val sqliteJdbc = mvn"org.xerial:sqlite-jdbc:3.49.1.0"
  val streamClient = mvn"com.rabbitmq:stream-client:0.23.0"
  val testcontainersBom = mvn"org.testcontainers:testcontainers-bom:1.21.3"
  val testcontainersRedis = mvn"com.redis:testcontainers-redis:2.2.4"
  val thymeleaf = mvn"org.thymeleaf:thymeleaf:3.1.3.RELEASE"
  val thymeleafExtrasDataAttribute =
    mvn"com.github.mxab.thymeleaf.extras:thymeleaf-extras-data-attribute:2.0.1"
  val thymeleafExtrasSpringsecurity6 =
    mvn"org.thymeleaf.extras:thymeleaf-extras-springsecurity6:3.1.3.RELEASE"
  val thymeleafLayoutDialect =
    mvn"nz.net.ultraq.thymeleaf:thymeleaf-layout-dialect:3.4.0"
  val thymeleafSpring6 = mvn"org.thymeleaf:thymeleaf-spring6:3.1.3.RELEASE"
  val tomcatAnnotationsApi =
    mvn"org.apache.tomcat:tomcat-annotations-api:10.1.46"
  val tomcatEmbedCore = mvn"org.apache.tomcat.embed:tomcat-embed-core:10.1.46"
  val tomcatEmbedEl = mvn"org.apache.tomcat.embed:tomcat-embed-el:10.1.46"
  val tomcatEmbedJasper =
    mvn"org.apache.tomcat.embed:tomcat-embed-jasper:10.1.46"
  val tomcatEmbedWebsocket =
    mvn"org.apache.tomcat.embed:tomcat-embed-websocket:10.1.46"
  val tomcatJdbc = mvn"org.apache.tomcat:tomcat-jdbc:10.1.46"
  val tomcatJspApi = mvn"org.apache.tomcat:tomcat-jsp-api:10.1.46"
  val trogdor = mvn"org.apache.kafka:trogdor:3.9.1"
  val ucp = mvn"com.oracle.database.jdbc:ucp:23.7.0.25.01"
  val ucp11 = mvn"com.oracle.database.jdbc:ucp11:23.7.0.25.01"
  val ucp17 = mvn"com.oracle.database.jdbc:ucp17:23.7.0.25.01"
  val unboundidLdapsdk = mvn"com.unboundid:unboundid-ldapsdk:7.0.3"
  val undertowCore = mvn"io.undertow:undertow-core:2.3.19.Final"
  val undertowServlet = mvn"io.undertow:undertow-servlet:2.3.19.Final"
  val undertowWebsocketsJsr =
    mvn"io.undertow:undertow-websockets-jsr:2.3.19.Final"
  val viburDbcp = mvn"org.vibur:vibur-dbcp:26.0"
  val viburObjectPool = mvn"org.vibur:vibur-object-pool:26.0"
  val webjarsLocatorCore = mvn"org.webjars:webjars-locator-core:0.59"
  val webjarsLocatorLite = mvn"org.webjars:webjars-locator-lite:1.1.0"
  val wsdl4j = mvn"wsdl4j:wsdl4j:1.6.3"
  val xdb = mvn"com.oracle.database.xml:xdb:23.7.0.25.01"
  val xmlparserv2 = mvn"com.oracle.database.xml:xmlparserv2:23.7.0.25.01"
  val xmlunitAssertj = mvn"org.xmlunit:xmlunit-assertj:2.10.4"
  val xmlunitAssertj3 = mvn"org.xmlunit:xmlunit-assertj3:2.10.4"
  val xmlunitCore = mvn"org.xmlunit:xmlunit-core:2.10.4"
  val xmlunitJakartaJaxbImpl = mvn"org.xmlunit:xmlunit-jakarta-jaxb-impl:2.10.4"
  val xmlunitLegacy = mvn"org.xmlunit:xmlunit-legacy:2.10.4"
  val xmlunitMatchers = mvn"org.xmlunit:xmlunit-matchers:2.10.4"
  val xmlunitPlaceholders = mvn"org.xmlunit:xmlunit-placeholders:2.10.4"
  val yasson = mvn"org.eclipse:yasson:3.0.4"
  val zipkinReporterBom = mvn"io.zipkin.reporter2:zipkin-reporter-bom:3.5.1"
}
