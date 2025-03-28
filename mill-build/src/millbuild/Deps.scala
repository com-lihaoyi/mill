package millbuild

import mill.scalalib.DepSyntax
import mill.scalalib.api.ZincWorkerUtil

object Deps {

  // The Scala version to use
  // When updating, run "Publish Bridges" Github Actions for the new version
  // and then add to it `bridgeScalaVersions`
  val scalaVersion = "3.6.2"
  val scala2Version = "2.13.15"
  // The Scala 2.12.x version to use for some workers
  val workerScalaVersion212 = "2.12.20"
  val sbtScalaVersion212 = workerScalaVersion212

  val testScala213Version = "2.13.15"
  // Scala Native 4.2 will not get releases for new Scala version
  val testScala213VersionForScalaNative42 = "2.13.8"
  val testScala212Version = "2.12.6"
  val testScala32Version = "3.2.0"
  val testScala33Version = "3.3.1"

  object Scalajs_1 {
    val scalaJsVersion = "1.18.1"
    val scalajsEnvJsdomNodejs =
      ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0".withDottyCompat(scalaVersion)
    val scalajsEnvExoegoJsdomNodejs =
      ivy"net.exoego::scalajs-env-jsdom-nodejs:2.1.0".withDottyCompat(scalaVersion)
    val scalajsEnvNodejs = ivy"org.scala-js::scalajs-env-nodejs:1.4.0".withDottyCompat(scalaVersion)
    val scalajsEnvPhantomjs =
      ivy"org.scala-js::scalajs-env-phantomjs:1.0.0".withDottyCompat(scalaVersion)
    val scalajsEnvSelenium =
      ivy"org.scala-js::scalajs-env-selenium:1.1.1".withDottyCompat(scalaVersion)
    val scalajsSbtTestAdapter =
      ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJsVersion}".withDottyCompat(scalaVersion)
    val scalajsLinker =
      ivy"org.scala-js::scalajs-linker:${scalaJsVersion}".withDottyCompat(scalaVersion)
    val scalajsImportMap =
      ivy"com.armanbilge::scalajs-importmap:0.1.1".withDottyCompat(scalaVersion)
  }

  object Scalanative_0_5 {
    val scalanativeVersion = "0.5.6"
    val scalanativeTools = ivy"org.scala-native::tools:${scalanativeVersion}"
    val scalanativeUtil = ivy"org.scala-native::util:${scalanativeVersion}"
    val scalanativeNir = ivy"org.scala-native::nir:${scalanativeVersion}"
    val scalanativeTestRunner = ivy"org.scala-native::test-runner:${scalanativeVersion}"
  }

  trait Play {
    def playVersion: String
    def playBinVersion: String = playVersion.split("[.]").take(2).mkString(".")
    def routesCompiler = playBinVersion match {
      case "2.6" | "2.7" | "2.8" => ivy"com.typesafe.play::routes-compiler::$playVersion"
      case "2.9" => ivy"com.typesafe.play::play-routes-compiler::$playVersion"
      case _ => ivy"org.playframework::play-routes-compiler::$playVersion"
    }
    def scalaVersion: String = Deps.scalaVersion
  }
  object Play_2_6 extends Play {
    def playVersion = "2.6.25"
    override def scalaVersion: String = Deps.workerScalaVersion212
  }
  object Play_2_7 extends Play {
    val playVersion = "2.7.9"
    override def scalaVersion: String = Deps.scala2Version
  }
  object Play_2_8 extends Play {
    val playVersion = "2.8.22"
    override def scalaVersion: String = Deps.scala2Version
  }
  object Play_2_9 extends Play {
    val playVersion = "2.9.6"
  }
  object Play_3_0 extends Play {
    val playVersion = "3.0.6"
  }
  val play =
    Seq(Play_3_0, Play_2_9, Play_2_8, Play_2_7, Play_2_6).map(p => (p.playBinVersion, p)).toMap

  val acyclic = ivy"com.lihaoyi:::acyclic:0.3.18"
  val ammoniteVersion = "3.0.2"
  val asmTree = ivy"org.ow2.asm:asm-tree:9.7.1"
  val bloopConfig = ivy"ch.epfl.scala::bloop-config:1.5.5".withDottyCompat(scalaVersion)

  val coursierVersion = "2.1.25-M4"
  val coursier = ivy"io.get-coursier::coursier:$coursierVersion".withDottyCompat(scalaVersion)
  val coursierInterface = ivy"io.get-coursier:interface:1.0.29-M1"
  val coursierJvm =
    ivy"io.get-coursier::coursier-jvm:$coursierVersion".withDottyCompat(scalaVersion)

  val cask = ivy"com.lihaoyi::cask:0.9.4"
  val castor = ivy"com.lihaoyi::castor:0.3.0"
  val fastparse = ivy"com.lihaoyi::fastparse:3.1.1"
  val flywayCore = ivy"org.flywaydb:flyway-core:8.5.13"
  val graphvizJava = Seq(
    ivy"guru.nidi:graphviz-java-min-deps:0.18.1",
    ivy"org.webjars.npm:viz.js-graphviz-java:2.1.3",
    ivy"org.apache.xmlgraphics:batik-rasterizer:1.18"
  )

  val jgraphtCore = ivy"org.jgrapht:jgrapht-core:1.4.0" // 1.5.0+ dont support JDK8
  val javet = Seq(
    ivy"com.caoccao.javet:javet:4.0.0",
    ivy"com.caoccao.javet:javet-linux-arm64:4.0.0",
    ivy"com.caoccao.javet:javet-macos:4.0.0"
  )

  val jline = ivy"org.jline:jline:3.28.0"
  val jnaVersion = "5.16.0"

  val jna = ivy"net.java.dev.jna:jna:${jnaVersion}"
  val jnaPlatform = ivy"net.java.dev.jna:jna-platform:${jnaVersion}"

  val junitInterface = ivy"com.github.sbt:junit-interface:0.13.3"
  val commonsIo = ivy"commons-io:commons-io:2.18.0"
  val log4j2Core = ivy"org.apache.logging.log4j:log4j-core:2.24.3"
  val osLib = ivy"com.lihaoyi::os-lib:0.11.5-M2"
  val pprint = ivy"com.lihaoyi::pprint:0.9.0"
  val mainargs = ivy"com.lihaoyi::mainargs:0.7.6"
  val millModuledefsVersion = "0.11.3-M5"
  val millModuledefsString = s"com.lihaoyi::mill-moduledefs:${millModuledefsVersion}"
  val millModuledefs = ivy"${millModuledefsString}"
  val millModuledefsPlugin =
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${millModuledefsVersion}"
  // can't use newer versions, as these need higher Java versions
  val testng = ivy"org.testng:testng:7.5.1"
  val sbtTestInterface = ivy"org.scala-sbt:test-interface:1.0"
  def scalaCompiler(scalaVersion: String) = {
    if (ZincWorkerUtil.isScala3(scalaVersion)) ivy"org.scala-lang:scala3-compiler_3:${scalaVersion}"
    else ivy"org.scala-lang:scala-compiler:${scalaVersion}"
  }
  val scalafmtDynamic = ivy"org.scalameta::scalafmt-dynamic:3.8.5".withDottyCompat(scalaVersion)
  def scalap(scalaVersion: String) = ivy"org.scala-lang:scalap:${scalaVersion}"
  def scalaReflect(scalaVersion: String) =
    if (ZincWorkerUtil.isScala3(scalaVersion))
      ivy"org.scala-lang:scala-reflect:${Deps.scala2Version}"
    else ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  val scoverage2Version = "2.2.1"
  val scalacScoverage2Plugin = ivy"org.scoverage:::scalac-scoverage-plugin:${scoverage2Version}"
  val scalacScoverage2Reporter = ivy"org.scoverage::scalac-scoverage-reporter:${scoverage2Version}"
  val scalacScoverage2Domain = ivy"org.scoverage::scalac-scoverage-domain:${scoverage2Version}"
  val scalacScoverage2Serializer =
    ivy"org.scoverage::scalac-scoverage-serializer:${scoverage2Version}"
  val scalaparse = ivy"com.lihaoyi::scalaparse:${fastparse.version}"
  val scalatags = ivy"com.lihaoyi::scalatags:0.13.1".withDottyCompat(scalaVersion)
  def scalaXml = ivy"org.scala-lang.modules::scala-xml:2.3.0"
  // keep in sync with doc/antora/antory.yml
  val semanticDBscala = ivy"org.scalameta:::semanticdb-scalac:4.12.4"
  val semanticDbJava = ivy"com.sourcegraph:semanticdb-java:0.10.3"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.4.3-M5"
  val upickle = ivy"com.lihaoyi::upickle:4.1.0"
  val windowsAnsi = ivy"io.github.alexarchambault.windows-ansi:windows-ansi:0.0.6"
  val zinc = ivy"org.scala-sbt::zinc:1.10.8".withDottyCompat(scalaVersion)
  // keep in sync with doc/antora/antory.yml
  val bsp4j = ivy"ch.epfl.scala:bsp4j:2.2.0-M2"
  val fansi = ivy"com.lihaoyi::fansi:0.5.0"
  val jarjarabrams =
    ivy"com.eed3si9n.jarjarabrams::jarjar-abrams-core:1.14.1".withDottyCompat(scalaVersion)
  val requests = ivy"com.lihaoyi::requests:0.9.0"
  val logback = ivy"ch.qos.logback:logback-classic:1.5.17"
  val sonatypeCentralClient = ivy"com.lumidion::sonatype-central-client-requests:0.3.0"
  val kotlinVersion = "2.0.21"
  val kotlinCompiler = ivy"org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion"
  val mavenVersion = "3.9.9"
  val mavenEmbedder = ivy"org.apache.maven:maven-embedder:$mavenVersion"
  val mavenResolverVersion = "1.9.22"
  val mavenResolverConnectorBasic =
    ivy"org.apache.maven.resolver:maven-resolver-connector-basic:$mavenResolverVersion"
  val mavenResolverSupplier =
    ivy"org.apache.maven.resolver:maven-resolver-supplier:$mavenResolverVersion"
  val mavenResolverTransportFile =
    ivy"org.apache.maven.resolver:maven-resolver-transport-file:$mavenResolverVersion"
  val mavenResolverTransportHttp =
    ivy"org.apache.maven.resolver:maven-resolver-transport-http:$mavenResolverVersion"
  val mavenResolverTransportWagon =
    ivy"org.apache.maven.resolver:maven-resolver-transport-wagon:$mavenResolverVersion"
  val coursierJvmIndexVersion = "0.0.4-84-f852c6"
  val gradleApi = ivy"dev.gradleplugins:gradle-api:8.11.1"
  val sbt = ivy"org.scala-sbt:sbt:1.10.10"

  object RuntimeDeps {
    val dokkaVersion = "2.0.0"
    val koverVersion = "0.8.3"

    val detektCli = ivy"io.gitlab.arturbosch.detekt:detekt-cli:1.23.7"
    val dokkaAnalysisDescriptors =
      ivy"org.jetbrains.dokka:analysis-kotlin-descriptors:$dokkaVersion"
    val dokkaBase = ivy"org.jetbrains.dokka:dokka-base:$dokkaVersion"
    val dokkaCli = ivy"org.jetbrains.dokka:dokka-cli:$dokkaVersion"
    val errorProneCore = ivy"com.google.errorprone:error_prone_core:2.31.0"
    val freemarker = ivy"org.freemarker:freemarker:2.3.34"
    val jupiterInterface = ivy"com.github.sbt.junit:jupiter-interface:0.13.3"
    val kotlinxHtmlJvm = ivy"org.jetbrains.kotlinx:kotlinx-html-jvm:0.11.0"
    val koverCli = ivy"org.jetbrains.kotlinx:kover-cli:$koverVersion"
    val koverJvmAgent = ivy"org.jetbrains.kotlinx:kover-jvm-agent:$koverVersion"
    val ktfmt = ivy"com.facebook:ktfmt:0.53"
    val ktlint = ivy"com.pinterest.ktlint:ktlint-core:0.49.1"
    val palantirFormat = ivy"com.palantir.javaformat:palantir-java-format:2.51.0"
    val proguard = ivy"com.guardsquare:proguard-base:7.7.0"
    val revApi = ivy"org.revapi:revapi-standalone:0.12.0"
    val sbtTestInterface = ivy"com.github.sbt:junit-interface:0.13.2"

    def all = Seq(
      detektCli,
      dokkaAnalysisDescriptors,
      dokkaBase,
      dokkaCli,
      errorProneCore,
      freemarker,
      jupiterInterface,
      kotlinxHtmlJvm,
      koverCli,
      koverJvmAgent,
      ktfmt,
      ktlint,
      palantirFormat,
      proguard,
      revApi,
      sbtTestInterface
    )
  }

  /** Used to manage transitive versions. */
  lazy val transitiveDeps = Seq(
    ivy"org.apache.ant:ant:1.10.15",
    Deps.commonsIo,
    ivy"com.google.code.gson:gson:2.11.0",
    ivy"com.google.protobuf:protobuf-java:4.29.3",
    ivy"com.google.guava:guava:33.4.0-jre",
    ivy"org.yaml:snakeyaml:2.3",
    ivy"org.apache.commons:commons-compress:1.27.1"
  )

  /** Used in tests. */
  object TestDeps {
    // tests framework (test)
    val scalaCheck = ivy"org.scalacheck::scalacheck:1.18.1"
    val scalaTest = ivy"org.scalatest::scalatest:3.2.19"
    val utest = ivy"com.lihaoyi::utest:0.8.5"
    val zioTest = ivy"dev.zio::zio-test:2.1.14"
  }

  /** Used in documentation. */
  object DocDeps {
    val millScip = ivy"io.chris-kipp::mill-scip_mill0.11:0.3.7"
  }
}
