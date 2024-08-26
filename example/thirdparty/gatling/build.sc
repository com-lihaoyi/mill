import mill._, scalalib._


object Dependencies {
  // Compile dependencies

  // format: OFF
  private def scalaReflect(version: String)  = ivy"org.scala-lang:scala-reflect:$version"
  private val gatlingSharedUtil              = ivy"io.gatling::gatling-shared-util:0.0.8"
  private val gatlingSharedModel             = ivy"io.gatling::gatling-shared-model:0.0.6"
  private val gatlingSharedCli               = ivy"io.gatling:gatling-shared-cli:0.0.3"
  private val scalaSwing                     = ivy"org.scala-lang.modules::scala-swing:3.0.0"
  private val scalaParserCombinators         = ivy"org.scala-lang.modules::scala-parser-combinators:2.4.0"
  private val netty                          = ivy"io.netty:netty-codec-http:4.1.112.Final"
  private val nettyBuffer                    = ivy"io.netty:netty-buffer:4.1.112.Final"
  private val nettyHandler                   = ivy"io.netty:netty-handler:4.1.112.Final"
  private val nettyProxy                     = ivy"io.netty:netty-handler-proxy:4.1.112.Final"
  private val nettyDns                       = ivy"io.netty:netty-resolver-dns:4.1.112.Final"
  private val nettyEpollLinuxX86             = ivy"io.netty:netty-transport-native-epoll:4.1.112.Final;classifier=linux-x86_64"
  private val nettyEpollLinuxArm             = ivy"io.netty:netty-transport-native-epoll:4.1.112.Final;classifier=linux-aarch_64"
  private val nettyIoUringLinuxX86           = ivy"io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.25.Final;classifier=linux-x86_64"
  private val nettyIoUringLinuxArm           = ivy"io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.25.Final;classifier=linux-aarch_64"
  private val nettyHttp2                     = ivy"io.netty:netty-codec-http2:4.1.112.Final"
  private val nettyResolverNativeOsXX86      = ivy"io.netty:netty-resolver-dns-native-macos:4.1.112.Final;classifier=osx-x86_64"
  private val nettyResolverNativeOsXArm      = ivy"io.netty:netty-resolver-dns-native-macos:4.1.112.Final;classifier=osx-aarch_64"
  private val nettyTcNative                  = ivy"io.netty:netty-tcnative-classes:2.0.65.Final"
  private val nettyTcNativeBoringSsl         = ivy"io.netty:netty-tcnative-boringssl-static:2.0.65.Final"
  private val nettyTcNativeBoringSslLinuxX86 = ivy"io.netty:netty-tcnative-boringssl-static:2.0.65.Final;classifier=linux-x86_64"
  private val nettyTcNativeBoringSslLinuxArm = ivy"io.netty:netty-tcnative-boringssl-static:2.0.65.Final;classifier=linux-aarch_64"
  private val nettyTcNativeBoringSslOsXX86   = ivy"io.netty:netty-tcnative-boringssl-static:2.0.65.Final;classifier=osx-x86_64"
  private val nettyTcNativeBoringSslOsXArm   = ivy"io.netty:netty-tcnative-boringssl-static:2.0.65.Final;classifier=osx-aarch_64"
  private val nettyTcNativeBoringSslWindows  = ivy"io.netty:netty-tcnative-boringssl-static:2.0.65.Final;classifier=windows-x86_64"
  private val brotli4j                       = ivy"com.aayushatharva.brotli4j:brotli4j:1.16.0"
  private val brotli4jLinuxX86               = ivy"com.aayushatharva.brotli4j:native-linux-x86_64:1.16.0"
  private val brotli4jLinuxArm               = ivy"com.aayushatharva.brotli4j:native-linux-aarch64:1.16.0"
  private val brotli4cOsXX86                 = ivy"com.aayushatharva.brotli4j:native-osx-x86_64:1.16.0"
  private val brotli4cOsXArm                 = ivy"com.aayushatharva.brotli4j:native-osx-aarch64:1.16.0"
  private val brotli4jWindows                = ivy"com.aayushatharva.brotli4j:native-windows-x86_64:1.16.0"
  private val config                         = ivy"com.typesafe:config:1.4.3"
  private val saxon                          = ivy"net.sf.saxon:Saxon-HE:10.6"
  private val slf4jApi                       = ivy"org.slf4j:slf4j-api:2.0.16"
  private val cfor                           = ivy"io.github.metarank::cfor:0.3"
  private val scopt                          = ivy"com.github.scopt::scopt:3.7.1"
  private val scalaLogging                   = ivy"com.typesafe.scala-logging::scala-logging:3.9.5"
  private val jackson                        = ivy"com.fasterxml.jackson.core:jackson-databind:2.17.2"
  private val sfm                            = ivy"org.simpleflatmapper:lightning-csv:8.2.3"
    .exclude(("org.simpleflatmapper", "ow2-asm"))
  private val lagarto                        = ivy"org.jodd:jodd-lagarto:6.0.6"
  private val joddUtil                       = ivy"org.jodd:jodd-util:6.2.2"
  private val jmespath                       = ivy"io.burt:jmespath-jackson:0.6.0"
  private val boopickle                      = ivy"io.suzaku::boopickle:1.4.0"
  private val redisClient                    = ivy"net.debasishg::redisclient:3.42"
  private val testInterface                  = ivy"org.scala-sbt:test-interface:1.0"
  private val jmsApi                         = ivy"javax.jms:javax.jms-api:2.0.1"
  private val logback                        = ivy"ch.qos.logback:logback-classic:1.5.7"
  private val tdigest                        = ivy"com.tdunning:t-digest:3.1"
  private val hdrHistogram                   = ivy"org.hdrhistogram:HdrHistogram:2.2.1"
  private val caffeine                       = ivy"com.github.ben-manes.caffeine:caffeine:3.1.8"
  private val bouncyCastle                   = ivy"io.gatling:gatling-recorder-bc-shaded:1.78.1"
  private val fastUuid                       = ivy"com.eatthepath:fast-uuid:0.2.0"
  private val pebble                         = ivy"io.pebbletemplates:pebble:3.2.2"
  private val spotbugs                       = ivy"com.github.spotbugs:spotbugs-annotations:4.8.6"
  private val typetools                      = ivy"net.jodah:typetools:0.6.3"

  // Test dependencies
  private val scalaTest                      = ivy"org.scalatest::scalatest:3.2.19"
  private val scalaTestScalacheck            = ivy"org.scalatestplus::scalacheck-1-16:3.2.14.0"
  private val scalaTestMockito               = ivy"org.scalatestplus::mockito-3-4:3.2.10.0"
  private val scalaCheck                     = ivy"org.scalacheck::scalacheck:1.18.0"
  private val mockitoCore                    = ivy"org.mockito:mockito-core:4.11.0"
  private val activemqBroker                 = ivy"org.apache.activemq:activemq-broker:5.18.5"
    .exclude(("org.apache.geronimo.specs", "geronimo-jms_1.1_spec"))
  private val h2                             = ivy"com.h2database:h2:2.3.232"
  private val jmh                            = ivy"org.openjdk.jmh:jmh-core:1.27"

  private val junit                          = ivy"org.junit.jupiter:junit-jupiter-api:5.11.0"
  private val junitEngine                    = ivy"org.junit.jupiter:junit-jupiter-engine:5.11.0"
  private val jupiterInterface               = ivy"net.aichler:jupiter-interface:0.11.1"

  private val jetty                          = ivy"org.eclipse.jetty:jetty-server:9.4.55.v20240627"
  private val jettyProxy                     = ivy"org.eclipse.jetty:jetty-proxy:9.4.55.v20240627"

  // Docs dependencies
  private val commonsLang                    = ivy"org.apache.commons:commons-lang3:3.16.0"
  private val commonsCodec                   = ivy"commons-codec:commons-codec:1.17.1"
  private val awsSecretsManager              = ivy"software.amazon.awssdk:secretsmanager:2.27.7"
  
  // format: ON
  private val loggingDeps = Seq(slf4jApi, scalaLogging, logback)
  val testDeps = Seq(
    scalaTest,
    scalaTestScalacheck,
    scalaTestMockito,
    scalaCheck,
    mockitoCore
  )
  private val parserDeps = Seq(jackson, saxon, lagarto, joddUtil, jmespath)

  // Dependencies by module
  private val gatlingGrpcVersion = "3.11.5"
  private val gatlingMqttVersion = "3.11.5"

  val nettyUtilDependencies =
    Seq(
      gatlingSharedUtil,
      nettyBuffer,
      nettyEpollLinuxX86,
      nettyEpollLinuxArm,
      nettyIoUringLinuxX86,
      nettyIoUringLinuxArm,
      junit,
      junitEngine,
      jupiterInterface
    )

  val sharedModelDependencies =
    Seq(gatlingSharedUtil, boopickle) ++ testDeps

  val commonsSharedUnstableDependencies = testDeps

  val commonsDependencies =
    Seq(gatlingSharedUtil, config, cfor) ++ loggingDeps ++ testDeps

  val jsonpathDependencies =
    Seq(gatlingSharedUtil, scalaParserCombinators, jackson) ++ testDeps

  def quicklensDependencies(scalaVersion: String) =
    Seq(scalaReflect(scalaVersion))

  val coreDependencies =
    Seq(
      gatlingSharedModel,
      gatlingSharedCli,
      sfm,
      caffeine,
      pebble,
      scalaParserCombinators,
      scopt,
      nettyHandler,
      nettyTcNative,
      nettyTcNativeBoringSsl,
      nettyTcNativeBoringSslLinuxX86,
      nettyTcNativeBoringSslLinuxArm,
      nettyTcNativeBoringSslOsXX86,
      nettyTcNativeBoringSslOsXArm,
      nettyTcNativeBoringSslWindows
    ) ++
      parserDeps ++ testDeps

  val defaultJavaDependencies =
    Seq(spotbugs, junit, junitEngine, jupiterInterface) ++ testDeps

  val coreJavaDependencies =
    Seq(typetools) ++ defaultJavaDependencies

  val redisDependencies = redisClient +: testDeps

  val httpClientDependencies = Seq(
    gatlingSharedUtil,
    netty,
    nettyBuffer,
    nettyHandler,
    nettyProxy,
    nettyDns,
    nettyEpollLinuxX86,
    nettyEpollLinuxArm,
    nettyHttp2,
    nettyResolverNativeOsXX86,
    nettyResolverNativeOsXArm,
    nettyTcNative,
    nettyTcNativeBoringSsl,
    nettyTcNativeBoringSslLinuxX86,
    nettyTcNativeBoringSslLinuxArm,
    nettyTcNativeBoringSslOsXX86,
    nettyTcNativeBoringSslOsXArm,
    nettyTcNativeBoringSslWindows,
    brotli4j,
    brotli4jLinuxX86,
    brotli4jLinuxArm,
    brotli4cOsXX86,
    brotli4cOsXArm,
    brotli4jWindows,
    junit,
    junitEngine,
    jupiterInterface,
    jetty,
    jettyProxy
  ) ++ loggingDeps

  val httpDependencies = Seq(saxon) ++ testDeps

  val jmsDependencies = Seq(jmsApi, fastUuid, activemqBroker) ++ testDeps

  val jdbcDependencies = h2 +: testDeps

  val chartsDependencies = tdigest +: testDeps

  val benchmarkDependencies = Seq(jmh)

  val recorderDependencies = Seq(gatlingSharedCli, scalaSwing, jackson, bouncyCastle, netty) ++ testDeps

  val testFrameworkDependencies = Seq(gatlingSharedCli, testInterface)

  val docSamplesDependencies =
    Seq(
      commonsLang,
      commonsCodec,
      awsSecretsManager,
      activemqBroker,
      ivy"io.gatling:gatling-grpc:$gatlingGrpcVersion",
      ivy"io.gatling:gatling-grpc-java:$gatlingGrpcVersion",
      ivy"io.gatling:gatling-mqtt:$gatlingMqttVersion",
      ivy"io.gatling:gatling-mqtt-java:$gatlingMqttVersion"
    )
}

trait GatlingModule extends SbtModule{

  def scalaVersion = "2.13.14"
  def testModuleDeps: Seq[JavaModule] = Nil
  object test extends SbtTests with TestModule.ScalaTest{
    def moduleDeps = super.moduleDeps ++ testModuleDeps
    def ivyDeps = Agg.from(Dependencies.testDeps)
  }
}

object `gatling-app` extends GatlingModule{
  def moduleDeps = Seq(
    `gatling-core`,
    `gatling-core-java`,
    `gatling-http`,
    `gatling-http-java`,
    `gatling-jms`,
    `gatling-jms-java`,
    `gatling-jdbc`,
    `gatling-jdbc-java`,
    `gatling-redis`,
    `gatling-redis-java`,
    `gatling-charts`
  )
  def ivyDeps = Agg[Dep]()
}
object `gatling-benchmarks` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`, `gatling-http`)
  def ivyDeps = Agg.from(Dependencies.benchmarkDependencies)
}
object `gatling-charts` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`)
  def testModuleDeps = Seq(`gatling-core`.test)
  def ivyDeps = Agg.from(Dependencies.chartsDependencies)
}
object `gatling-commons` extends GatlingModule{
  def moduleDeps = Seq()
  def ivyDeps = Agg.from(Dependencies.commonsDependencies)
}
object `gatling-core` extends GatlingModule{
  def moduleDeps = Seq(
    `gatling-netty-util`,
    `gatling-quicklens`,
    `gatling-commons`,
    `gatling-jsonpath`
  )
  def testModuleDeps = Seq(
    `gatling-commons`.test,
    `gatling-jsonpath`.test
  )
  def ivyDeps = Agg.from(Dependencies.coreDependencies)
}
object `gatling-core-java` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`)
  def testModuleDeps = Seq(`gatling-core`.test)
  def ivyDeps = Agg.from(Dependencies.coreJavaDependencies)
}
object `gatling-http` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`, `gatling-http-client`)
  def testModuleDeps = Seq(`gatling-core`.test, `gatling-http-client`.test)
  def ivyDeps = Agg.from(Dependencies.httpDependencies)
}
object `gatling-http-client` extends GatlingModule{
  def moduleDeps = Seq(`gatling-netty-util`)
  def testModuleDeps = Seq(`gatling-netty-util`.test)
  def ivyDeps = Agg.from(Dependencies.httpClientDependencies)
}
object `gatling-http-java` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core-java`, `gatling-http`)
  def testModuleDeps = Seq(`gatling-http`.test)
  def ivyDeps = Agg.from(Dependencies.defaultJavaDependencies)
}
object `gatling-jdbc` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`)
  def testModuleDeps = Seq(`gatling-core`.test)
  def ivyDeps = Agg.from(Dependencies.jdbcDependencies)
}
object `gatling-jdbc-java` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core-java`, `gatling-jdbc`)
  def testModuleDeps = Seq(`gatling-jdbc`.test)
  def ivyDeps = Agg.from(Dependencies.defaultJavaDependencies)
}
object `gatling-jms` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`)
  def testModuleDeps = Seq(`gatling-core`.test)
  def ivyDeps = Agg.from(Dependencies.jmsDependencies)
}
object `gatling-jms-java` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core-java`, `gatling-jms`)
  def testModuleDeps = Seq(`gatling-jms`.test)
  def ivyDeps = Agg.from(Dependencies.defaultJavaDependencies)
}
object `gatling-jsonpath` extends GatlingModule{
  def moduleDeps = Seq()
  def ivyDeps = Agg.from(Dependencies.jsonpathDependencies)
}
object `gatling-netty-util` extends GatlingModule{
  def moduleDeps = Seq()
  def ivyDeps = Agg.from(Dependencies.nettyUtilDependencies)
}
object `gatling-quicklens` extends GatlingModule{
  def moduleDeps = Seq()
  def ivyDeps = Agg.from(Dependencies.quicklensDependencies(scalaVersion()))
}
object `gatling-recorder` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`, `gatling-http`)
  def testModuleDeps = Seq(`gatling-core`.test)
  def ivyDeps = Agg.from(Dependencies.recorderDependencies)
}
object `gatling-redis` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core`)
  def testModuleDeps = Seq(`gatling-core`.test)
  def ivyDeps = Agg.from(Dependencies.redisDependencies)
}
object `gatling-redis-java` extends GatlingModule{
  def moduleDeps = Seq(`gatling-core-java`, `gatling-redis`)
  def testModuleDeps = Seq(`gatling-redis`.test)
  def ivyDeps = Agg.from(Dependencies.defaultJavaDependencies)
}
object `gatling-samples` extends GatlingModule{
  def moduleDeps = Seq(`gatling-app`)
  def ivyDeps = Agg[Dep]()
}
object `gatling-test-framework` extends GatlingModule{
  def moduleDeps = Seq(`gatling-app`)
  def ivyDeps = Agg.from(Dependencies.testFrameworkDependencies)
}

/** Usage

> sed -i.bak 's/1.seconds/10.seconds/g' gatling-core/src/test/scala/io/gatling/core/actor/ActorSpec.scala

> sed -i.bak 's/is.toString(charset)/is.toString()/g' gatling-benchmarks/src/main/scala/io/gatling/Utils.scala

> ./mill __.test
ConsoleTemplateSpec:
console template
- should format the request counters properly
- should format the grouped counts properly
RampConcurrentUsersInjection
- should return the correct number of users target
- should inject once a full user is reached
composite.injectionSteps
- should produce the expected injection profile with starting users and with ramps
- should produce the expected injection profile without starting users and without ramps
- should produce the expected injection profile with starting users and without ramps
- should produce the expected injection profile without starting users and with ramps
JmsSimpleCheckSpec:
simple check
- should return success if condition is true
- should return failure if condition is false
- should return failure if message is not TextMessage
JmsJsonPathCheckSpec:
jsonPath.find for TextMessage
- should support long values
jsonPath.find.exists for TextMessage
- should find single result into JSON serialized form
- should find single result into Map object form
- should find a null attribute value when expected type is String
- should find a null attribute value when expected type is Any
- should find a null attribute value when expected type is Int
- should find a null attribute value when expected type is Seq
- should find a null attribute value when expected type is Map
- should succeed when expecting a null value and getting a null one
- should fail when expecting a null value and getting a non-null one
- should succeed when expecting a non-null value and getting a non-null one
- should fail when expecting a non-null value and getting a null one
- should not fail on empty array
...

*/