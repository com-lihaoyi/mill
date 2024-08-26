// imports
import scala.util.chaining._
import com.github.lolgab.mill.mima.Mima
import coursier.maven.MavenRepository
import de.tobiasroeser.mill.vcs.version.VcsVersion
import com.goyeau.mill.scalafix.ScalafixModule
import example.millSourcePath
import mill._
import mill.api.JarManifest
import mill.define.NamedTask
import mill.main.Tasks
import mill.scalalib._
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.publish._
import mill.util.Jvm
import mill.resolve.SelectMode
import mill.contrib.buildinfo.BuildInfo
import mill.T
import mill.define.Cross

// plugins and dependencies
import $meta._
import $file.ci.shared
import $file.ci.upload

object Settings {
  val pomOrg = "com.lihaoyi"
  val githubOrg = "com-lihaoyi"
  val githubRepo = "mill"
  val projectUrl = s"https://github.com/${githubOrg}/${githubRepo}"
  val changelogUrl = s"${projectUrl}#changelog"
  val newIssueUrl = s"${projectUrl}/issues/new/choose"
  val docUrl = "https://mill-build.org"
  // the exact branches containing a doc root
  val docBranches = Seq()
  // the exact tags containing a doc root
  val legacyDocTags: Seq[String] = Seq(
    "0.9.12",
    "0.10.0",
    "0.10.12",
    "0.10.15",
    "0.11.0-M7"
  )
  val docTags: Seq[String] = Seq(
    "0.11.10",
    "0.11.11",
    "0.11.12"
  )
  val mimaBaseVersions: Seq[String] = 0.to(12).map("0.11." + _)
}

object Deps {

  // The Scala version to use
  // When updating, run "Publish Bridges" Github Actions for the new version
  // and then add to it `bridgeScalaVersions`
  val scalaVersion = "2.13.14"
  // Scoverage 1.x will not get releases for newer Scala versions
  val scalaVersionForScoverageWorker1 = "2.13.8"
  // The Scala 2.12.x version to use for some workers
  val workerScalaVersion212 = "2.12.19"

  val testScala213Version = "2.13.14"
  // Scala Native 4.2 will not get releases for new Scala version
  val testScala213VersionForScalaNative42 = "2.13.8"
  val testScala212Version = "2.12.6"
  val testScala32Version = "3.2.0"
  val testScala33Version = "3.3.1"

  object Scalajs_1 {
    val scalaJsVersion = "1.16.0"
    val scalajsEnvJsdomNodejs = ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0"
    val scalajsEnvExoegoJsdomNodejs = ivy"net.exoego::scalajs-env-jsdom-nodejs:2.1.0"
    val scalajsEnvNodejs = ivy"org.scala-js::scalajs-env-nodejs:1.4.0"
    val scalajsEnvPhantomjs = ivy"org.scala-js::scalajs-env-phantomjs:1.0.0"
    val scalajsEnvSelenium = ivy"org.scala-js::scalajs-env-selenium:1.1.1"
    val scalajsSbtTestAdapter = ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJsVersion}"
    val scalajsLinker = ivy"org.scala-js::scalajs-linker:${scalaJsVersion}"
    val scalajsImportMap = ivy"com.armanbilge::scalajs-importmap:0.1.1"
  }

  object Scalanative_0_5 {
    val scalanativeVersion = "0.5.4"
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
  }
  object Play_2_8 extends Play {
    val playVersion = "2.8.22"
  }
  object Play_2_9 extends Play {
    val playVersion = "2.9.5"
  }
  object Play_3_0 extends Play {
    val playVersion = "3.0.5"
  }
  val play =
    Seq(Play_3_0, Play_2_9, Play_2_8, Play_2_7, Play_2_6).map(p => (p.playBinVersion, p)).toMap

  val acyclic = ivy"com.lihaoyi:::acyclic:0.3.12"
  val ammoniteVersion = "3.0.0-M2-15-9bed9700"
  val asmTree = ivy"org.ow2.asm:asm-tree:9.7"
  val bloopConfig = ivy"ch.epfl.scala::bloop-config:1.5.5"

  val coursier = ivy"io.get-coursier::coursier:2.1.10"
  val coursierInterface = ivy"io.get-coursier:interface:1.0.19"

  val cask = ivy"com.lihaoyi::cask:0.9.4"
  val castor = ivy"com.lihaoyi::castor:0.3.0"
  val fastparse = ivy"com.lihaoyi::fastparse:3.1.1"
  val flywayCore = ivy"org.flywaydb:flyway-core:8.5.13"
  val graphvizJava = ivy"guru.nidi:graphviz-java-all-j2v8:0.18.1"
  val junixsocket = ivy"com.kohlschutter.junixsocket:junixsocket-core:2.10.0"

  val jgraphtCore = ivy"org.jgrapht:jgrapht-core:1.4.0" // 1.5.0+ dont support JDK8

  val jline = ivy"org.jline:jline:3.26.3"
  val jnaVersion = "5.14.0"
  val jna = ivy"net.java.dev.jna:jna:${jnaVersion}"
  val jnaPlatform = ivy"net.java.dev.jna:jna-platform:${jnaVersion}"

  val junitInterface = ivy"com.github.sbt:junit-interface:0.13.3"
  val commonsIO = ivy"commons-io:commons-io:2.16.1"
  val lambdaTest = ivy"de.tototec:de.tobiasroeser.lambdatest:0.8.0"
  val log4j2Core = ivy"org.apache.logging.log4j:log4j-core:2.23.1"
  val osLib = ivy"com.lihaoyi::os-lib:0.10.4"
  val pprint = ivy"com.lihaoyi::pprint:0.9.0"
  val mainargs = ivy"com.lihaoyi::mainargs:0.7.1"
  val millModuledefsVersion = "0.10.9"
  val millModuledefsString = s"com.lihaoyi::mill-moduledefs:${millModuledefsVersion}"
  val millModuledefs = ivy"${millModuledefsString}"
  val millModuledefsPlugin =
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${millModuledefsVersion}"
  // can't use newer versions, as these need higher Java versions
  val testng = ivy"org.testng:testng:7.5.1"
  val sbtTestInterface = ivy"org.scala-sbt:test-interface:1.0"
  def scalaCompiler(scalaVersion: String) = ivy"org.scala-lang:scala-compiler:${scalaVersion}"
  // last scalafmt release supporting Java 8 is 3.7.15
  val scalafmtDynamic = ivy"org.scalameta::scalafmt-dynamic:3.7.15" // scala-steward:off
  def scalap(scalaVersion: String) = ivy"org.scala-lang:scalap:${scalaVersion}"
  def scalaReflect(scalaVersion: String) = ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  val scalacScoveragePlugin = ivy"org.scoverage:::scalac-scoverage-plugin:1.4.11"
  val scoverage2Version = "2.1.1"
  val scalacScoverage2Plugin = ivy"org.scoverage:::scalac-scoverage-plugin:${scoverage2Version}"
  val scalacScoverage2Reporter = ivy"org.scoverage::scalac-scoverage-reporter:${scoverage2Version}"
  val scalacScoverage2Domain = ivy"org.scoverage::scalac-scoverage-domain:${scoverage2Version}"
  val scalacScoverage2Serializer =
    ivy"org.scoverage::scalac-scoverage-serializer:${scoverage2Version}"
  val scalaparse = ivy"com.lihaoyi::scalaparse:${fastparse.version}"
  val scalatags = ivy"com.lihaoyi::scalatags:0.12.0"
  def scalaXml = ivy"org.scala-lang.modules::scala-xml:2.3.0"
  // keep in sync with doc/antora/antory.yml
  val semanticDBscala = ivy"org.scalameta:::semanticdb-scalac:4.9.9"
  val semanticDbJava = ivy"com.sourcegraph:semanticdb-java:0.10.0"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.3.1"
  val upickle = ivy"com.lihaoyi::upickle:3.3.1"
  val windowsAnsi = ivy"io.github.alexarchambault.windows-ansi:windows-ansi:0.0.5"
  val zinc = ivy"org.scala-sbt::zinc:1.10.1"
  // keep in sync with doc/antora/antory.yml
  val bsp4j = ivy"ch.epfl.scala:bsp4j:2.2.0-M2"
  val fansi = ivy"com.lihaoyi::fansi:0.5.0"
  val jarjarabrams = ivy"com.eed3si9n.jarjarabrams::jarjar-abrams-core:1.14.0"
  val requests = ivy"com.lihaoyi::requests:0.9.0"
  val sonatypeCentralClient = ivy"com.lumidion::sonatype-central-client-requests:0.3.0"

  object RuntimeDeps {
    val sbtTestInterface = ivy"com.github.sbt:junit-interface:0.13.2"
    val jupiterInterface = ivy"com.github.sbt.junit:jupiter-interface:0.11.4"
    def all = Seq(sbtTestInterface, jupiterInterface)
  }

  /** Used to manage transitive versions. */
  val transitiveDeps = Seq(
    ivy"org.apache.ant:ant:1.10.14",
    ivy"commons-io:commons-io:2.16.1",
    ivy"com.google.code.gson:gson:2.11.0",
    ivy"com.google.protobuf:protobuf-java:3.25.4",
    ivy"com.google.guava:guava:33.3.0-jre",
    ivy"org.yaml:snakeyaml:2.2",
    ivy"org.apache.commons:commons-compress:1.26.2"
  )

  /** Used in tests. */
  object TestDeps {
    // tests framework (test)
    val scalaCheck = ivy"org.scalacheck::scalacheck:1.18.0"
    val scalaTest = ivy"org.scalatest::scalatest:3.2.19"
    val utest = ivy"com.lihaoyi::utest:0.8.4"
    val zioTest = ivy"dev.zio::zio-test:2.0.22"
  }

  /** Used in documentation. */
  object DocDeps {
    val millScip = ivy"io.chris-kipp::mill-scip_mill0.11:0.3.7"
  }
}

def millVersion: T[String] = T { VcsVersion.vcsState().format() }

def millLastTag: T[String] = T {
  VcsVersion.vcsState().lastTag.getOrElse(
    sys.error("No (last) git tag found. Your git history seems incomplete!")
  )
}

def millBinPlatform: T[String] = T {
  //val tag = millLastTag()
  //if (tag.contains("-M")) tag
  //else {
  //  val pos = if (tag.startsWith("0.")) 2 else 1
  //  tag.split("[.]", pos + 1).take(pos).mkString(".")
  //}
  "0.11"
}

def baseDir = build.millSourcePath

val essentialBridgeScalaVersions =
  Seq(Deps.scalaVersion, Deps.scalaVersionForScoverageWorker1, Deps.workerScalaVersion212)
// published compiler bridges
val bridgeScalaVersions = Seq(
  // Our version of Zinc doesn't work with Scala 2.12.0 and 2.12.4 compiler
  // bridges. We skip 2.12.1 because it's so old not to matter, and we need a
  // non-supported scala version for testing purposes. We skip 2.13.0-2 because
  // scaladoc fails on windows
  /*"2.12.0",*/ /*2.12.1",*/ "2.12.2",
  "2.12.3", /*"2.12.4",*/ "2.12.5",
  "2.12.6",
  "2.12.7",
  "2.12.8",
  "2.12.9",
  "2.12.10",
  "2.12.11",
  "2.12.12",
  "2.12.13",
  "2.12.14",
  "2.12.15",
  "2.12.16",
  "2.12.17",
  "2.12.18",
  "2.12.19",
  /*"2.13.0", "2.13.1", "2.13.2",*/
  "2.13.3",
  "2.13.4",
  "2.13.5",
  "2.13.6",
  "2.13.7",
  "2.13.8",
  "2.13.9",
  "2.13.10",
  "2.13.11",
  "2.13.12",
  "2.13.13",
  "2.13.14"
)

// We limit the number of compiler bridges to compile and publish for local
// development and testing, because otherwise it takes forever to compile all
// of them. Compiler bridges not in this set will get downloaded and compiled
// on the fly anyway. For publishing, we publish everything or a specific version
// if given.
val compilerBridgeScalaVersions =
  interp.watchValue(sys.env.get("MILL_COMPILER_BRIDGE_VERSIONS")) match {
    case None | Some("") | Some("none") => Seq.empty[String]
    case Some("all") => (essentialBridgeScalaVersions ++ bridgeScalaVersions).distinct
    case Some("essential") => essentialBridgeScalaVersions
    case Some(versions) => versions.split(',').map(_.trim()).filterNot(_.isEmpty).toSeq
  }
val bridgeVersion = "0.0.1"

trait MillJavaModule extends JavaModule {

  // Test setup
  def testDep = T { (s"com.lihaoyi-${artifactId()}", testDepPaths().map(_.path).mkString("\n")) }

  // Workaround for Zinc/JNA bug
  // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
  def testArgs: T[Seq[String]] = T { Seq("-Djna.nosys=true") }
  def testDepPaths = T { upstreamAssemblyClasspath() ++ Seq(compile().classes) ++ resources() }

  def testTransitiveDeps: T[Map[String, String]] = T {
    val upstream = T.traverse(moduleDeps ++ compileModuleDeps) {
      case m: MillJavaModule => m.testTransitiveDeps.map(Some(_))
      case _ => T.task(None)
    }().flatten.flatten
    val current = Seq(testDep())
    upstream.toMap ++ current
  }


  def testIvyDeps: T[Agg[Dep]] = Agg(Deps.TestDeps.utest)
  def testModuleDeps: Seq[JavaModule] =
    if (this == main) Seq(main)
    else Seq(this, main.test)

  def writeLocalTestOverrides = T.task {
    for ((k, v) <- testTransitiveDeps()) {
      os.write(T.dest / "mill" / "local-test-overrides" / k, v, createFolders = true)
    }
    Seq(PathRef(T.dest))
  }

  def runClasspath = super.runClasspath() ++ writeLocalTestOverrides()

  def repositoriesTask = T.task {
    super.repositoriesTask() ++
      Seq(MavenRepository("https://oss.sonatype.org/content/repositories/releases"))
  }

  def mapDependencies: Task[coursier.Dependency => coursier.Dependency] = T.task {
    super.mapDependencies().andThen { dep =>
      forcedVersions.find(f =>
        f.dep.module.organization.value == dep.module.organization.value &&
          f.dep.module.name.value == dep.module.name.value
      ).map { forced =>
        val newDep = dep.withVersion(forced.dep.version)
        T.log.debug(s"Forcing version of ${dep.module} from ${dep.version} to ${newDep.version}")
        newDep
      }.getOrElse(dep)
    }
  }
  val forcedVersions: Seq[Dep] = Deps.transitiveDeps ++ Seq(
    Deps.jline,
    Deps.jna
  )
}

trait MillPublishJavaModule extends MillJavaModule with PublishModule {
  def commonPomSettings(artifactName: String) = {
    PomSettings(
      description = artifactName,
      organization = Settings.pomOrg,
      url = Settings.projectUrl,
      licenses = Seq(License.MIT),
      versionControl = VersionControl.github(Settings.githubOrg, Settings.githubRepo),
      developers = Seq(
        Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"),
        Developer("lefou", "Tobias Roeser", "https://github.com/lefou")
      )
    )
  }

  def artifactName = "mill-" + super.artifactName()
  def publishVersion = millVersion()
  def publishProperties = super.publishProperties() ++ Map(
    "info.releaseNotesURL" -> Settings.changelogUrl
  )
  def pomSettings = commonPomSettings(artifactName())
  def javacOptions = Seq("-source", "1.8", "-target", "1.8", "-encoding", "UTF-8")
}

/**
 * Some custom scala settings and test convenience
 */
trait MillScalaModule extends ScalaModule with MillJavaModule with ScalafixModule { outer =>
  def scalaVersion = Deps.scalaVersion
  def scalafixScalaBinaryVersion = ZincWorkerUtil.scalaBinaryVersion(scalaVersion())
  def semanticDbVersion = Deps.semanticDBscala.version
  def scalacOptions =
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-P:acyclic:force",
      "-feature",
      "-Xlint:unused",
      "-Xlint:adapted-args"
    )

  def scalacPluginIvyDeps =
    super.scalacPluginIvyDeps() ++
      Agg(Deps.acyclic) ++
      Agg.when(scalaVersion().startsWith("2.13."))(Deps.millModuledefsPlugin)

  def mandatoryIvyDeps =
    super.mandatoryIvyDeps() ++
      Agg.when(scalaVersion().startsWith("2.13."))(Deps.millModuledefs)

  /** Default tests module. */
  lazy val test: MillScalaTests = new MillScalaTests {}
  trait MillScalaTests extends ScalaTests with MillBaseTestsModule {
    def forkArgs = super.forkArgs() ++ outer.testArgs()
    def moduleDeps = outer.testModuleDeps
    def ivyDeps = super.ivyDeps() ++ outer.testIvyDeps()
    def forkEnv = super.forkEnv() ++ outer.forkEnv()
  }
}

trait MillBaseTestsModule extends MillJavaModule with TestModule {
  def forkArgs = T {
    Seq(
      s"-DMILL_SCALA_2_13_VERSION=${Deps.scalaVersion}",
      s"-DMILL_SCALA_2_12_VERSION=${Deps.workerScalaVersion212}",
      s"-DTEST_SCALA_2_13_VERSION=${Deps.testScala213Version}",
      s"-DTEST_SCALA_2_13_VERSION_FOR_SCALANATIVE_4_2=${Deps.testScala213VersionForScalaNative42}",
      s"-DTEST_SCALA_2_12_VERSION=${Deps.testScala212Version}",
      s"-DTEST_SCALA_3_2_VERSION=${Deps.testScala32Version}",
      s"-DTEST_SCALA_3_3_VERSION=${Deps.testScala33Version}",
      s"-DTEST_SCALAJS_VERSION=${Deps.Scalajs_1.scalaJsVersion}",
      s"-DTEST_SCALANATIVE_0_5_VERSION=${Deps.Scalanative_0_5.scalanativeVersion}",
      s"-DTEST_UTEST_VERSION=${Deps.TestDeps.utest.dep.version}",
      s"-DTEST_SCALATEST_VERSION=${Deps.TestDeps.scalaTest.dep.version}",
      s"-DTEST_TEST_INTERFACE_VERSION=${Deps.sbtTestInterface.dep.version}",
      s"-DTEST_ZIOTEST_VERSION=${Deps.TestDeps.zioTest.dep.version}",
      s"-DTEST_ZINC_VERSION=${Deps.zinc.dep.version}"
    )
  }

  def testFramework = "mill.UTestFramework"
}

/** Published module which does not contain strictly handled API. */
trait MillPublishScalaModule extends MillScalaModule with MillPublishJavaModule

/** Publishable module which contains strictly handled API. */
trait MillStableScalaModule extends MillPublishScalaModule with Mima {
  import com.github.lolgab.mill.mima._
  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq(
    // (5x) MIMA doesn't properly ignore things which are nested inside other private things
    // so we have to put explicit ignores here (https://github.com/lightbend/mima/issues/771)
    ProblemFilter.exclude[Problem]("mill.eval.ProfileLogger*"),
    ProblemFilter.exclude[Problem]("mill.eval.GroupEvaluator*"),
    ProblemFilter.exclude[Problem]("mill.eval.Tarjans*"),
    ProblemFilter.exclude[Problem]("mill.define.Ctx#Impl*"),
    ProblemFilter.exclude[Problem]("mill.resolve.ResolveNotFoundHandler*"),
    // (4x) See https://github.com/com-lihaoyi/mill/pull/2739
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalajslib.ScalaJSModule.mill$scalajslib$ScalaJSModule$$super$scalaLibraryIvyDeps"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.ScalaModule.mill$scalalib$ScalaModule$$super$zincAuxiliaryClassFileExtensions"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalajslib.ScalaJSModule.mill$scalajslib$ScalaJSModule$$super$zincAuxiliaryClassFileExtensions"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalanativelib.ScalaNativeModule.mill$scalanativelib$ScalaNativeModule$$super$zincAuxiliaryClassFileExtensions"
    ),
    // (6x) See https://github.com/com-lihaoyi/mill/pull/3064
    // Moved targets up in trait hierarchy, but also call them via super, which I think is safe
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$zincWorker"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runClasspath"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runUseArgsFile"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$forkArgs"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$forkEnv"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$forkWorkingDir"
    ),
    // (8x)
    // Moved targets up in trait hierarchy, but also call them via super, which I think is safe
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$localRunClasspath"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runLocal"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$run"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$doRunBackground"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runBackgroundLogToConsole"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runMainBackground"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runMainLocal"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runMain"
    ),
    // Terminal is sealed, not sure why MIMA still complains
    ProblemFilter.exclude[ReversedMissingMethodProblem]("mill.eval.Terminal.task"),

    // Not sure why mima is picking up this stuff which is private[mill]
    ProblemFilter.exclude[Problem]("mill.resolve.*.resolve0"),
    ProblemFilter.exclude[Problem]("mill.resolve.*.resolveRootModule"),

    // These methods are private so it doesn't matter
    ProblemFilter.exclude[ReversedMissingMethodProblem]("mill.resolve.Resolve.handleResolved"),
    ProblemFilter.exclude[Problem]("mill.resolve.*.resolveNonEmptyAndHandle*"),
    ProblemFilter.exclude[Problem]("mill.resolve.ResolveCore*"),
    ProblemFilter.exclude[InheritedNewAbstractMethodProblem](
      "mill.main.MainModule.mill$define$BaseModule0$_setter_$watchedValues_="
    ),
    ProblemFilter.exclude[InheritedNewAbstractMethodProblem](
      "mill.main.MainModule.mill$define$BaseModule0$_setter_$evalWatchedValues_="
    )
  )
  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaPreviousArtifacts: T[Agg[Dep]] = T {
    Agg.from(
      Settings.mimaBaseVersions
        .filter(v => !skipPreviousVersions().contains(v))
        .map(version =>
          ivy"${pomSettings().organization}:${artifactId()}:${version}"
        )
    )
  }

  def mimaExcludeAnnotations = Seq("mill.api.internal", "mill.api.experimental")
  def mimaCheckDirection = CheckDirection.Backward
  def skipPreviousVersions: T[Seq[String]] = T(Seq.empty[String])
}

object bridge extends Cross[BridgeModule](compilerBridgeScalaVersions)
trait BridgeModule extends MillPublishJavaModule with CrossScalaModule {
  def scalaVersion = crossScalaVersion
  def publishVersion = bridgeVersion
  def artifactName = "mill-scala-compiler-bridge"
  def pomSettings = commonPomSettings(artifactName())
  def crossFullScalaVersion = true
  def ivyDeps = Agg(
    ivy"org.scala-sbt:compiler-interface:${Deps.zinc.version}",
    ivy"org.scala-sbt:util-interface:${Deps.zinc.version}",
    ivy"org.scala-lang:scala-compiler:${crossScalaVersion}"
  )

  def resources = T.sources {
    os.copy(generatedSources().head.path / "META-INF", T.dest / "META-INF")
    Seq(PathRef(T.dest))
  }

  def compilerBridgeIvyDeps: T[Agg[Dep]] = Agg(
    ivy"org.scala-sbt::compiler-bridge:${Deps.zinc.version}".exclude("*" -> "*")
  )

  def compilerBridgeSourceJars: T[Agg[PathRef]] = T {
    resolveDeps(
      T.task { compilerBridgeIvyDeps().map(bindDependency()) },
      sources = true
    )()
  }

  def generatedSources = T {
    compilerBridgeSourceJars().foreach { jar =>
      mill.api.IO.unpackZip(jar.path, os.rel)
    }

    Seq(PathRef(T.dest))
  }
}

object main extends MillStableScalaModule with BuildInfo {

  def moduleDeps = Seq(eval, resolve, client)
  def ivyDeps = Agg(
    Deps.windowsAnsi,
    Deps.mainargs,
    Deps.coursierInterface,
    Deps.requests
  )

  def compileIvyDeps = Agg(Deps.scalaReflect(scalaVersion()))

  def buildInfoPackageName = "mill.main"

  def buildInfoMembers = Seq(
    BuildInfo.Value("scalaVersion", scalaVersion(), "Scala version used to compile mill core."),
    BuildInfo.Value(
      "workerScalaVersion212",
      Deps.workerScalaVersion212,
      "Scala 2.12 version used by some workers."
    ),
    BuildInfo.Value("millVersion", millVersion(), "Mill version."),
    BuildInfo.Value("millBinPlatform", millBinPlatform(), "Mill binary platform version."),
    BuildInfo.Value(
      "millEmbeddedDeps",
      (
        T.traverse(
          dist.recursiveModuleDeps.collect { case m: PublishModule => m }
        )(
          _.publishSelfDependency
        )()
          .map(artifact => s"${artifact.group}:${artifact.id}:${artifact.version}") ++
          Lib.resolveDependenciesMetadata(
            repositories = dist.repositoriesTask(),
            dist.transitiveIvyDeps(),
            Some(dist.mapDependencies()),
            dist.resolutionCustomizer(),
            Some(T.ctx()),
            dist.coursierCacheCustomizer()
          )._2.minDependencies.toSeq
            .map(d => s"${d.module.organization.value}:${d.module.name.value}:${d.version}")
      )
//      T.traverse(dev.moduleDeps)(_.publishSelfDependency)()
//        .map(artifact => s"${artifact.group}:${artifact.id}:${artifact.version}")
        .mkString(","),
      "Dependency artifacts embedded in mill assembly by default."
    ),
    BuildInfo.Value(
      "millScalacPluginDeps",
      Deps.millModuledefsString,
      "Scalac compiler plugin dependencies to compile the build script."
    )
  )

  object api extends MillStableScalaModule with BuildInfo {
    def moduleDeps = Seq(client)
    def buildInfoPackageName = "mill.api"
    def buildInfoMembers = Seq(
      BuildInfo.Value("millVersion", millVersion(), "Mill version."),
      BuildInfo.Value("millDocUrl", Settings.docUrl, "Mill documentation url."),
      BuildInfo.Value(
        "millReportNewIssueUrl",
        Settings.newIssueUrl,
        "URL to create a new issue in Mills issue tracker."
      )
    )

    def ivyDeps = Agg(
      Deps.osLib,
      Deps.upickle,
      Deps.pprint,
      Deps.fansi,
      Deps.sbtTestInterface
    )
  }

  object util extends MillStableScalaModule {
    def moduleDeps = Seq(api, client)
    def ivyDeps = Agg(Deps.coursier, Deps.jline)
  }

  object codesig extends MillPublishScalaModule {
    override def ivyDeps =
      Agg(Deps.asmTree, Deps.osLib, Deps.pprint)
    def moduleDeps = Seq(util)

    override lazy val test: CodeSigTests = new CodeSigTests {}
    trait CodeSigTests extends MillScalaTests {
      val caseKeys = interp.watchValue(
        os.walk(millSourcePath / "cases", maxDepth = 3)
          .map(_.subRelativeTo(millSourcePath / "cases").segments)
          .collect { case Seq(a, b, c) => s"$a-$b-$c" }
      )

      def testLogFolder = T { T.dest }

      def caseEnvs[V](f1: CaseModule => Task[V])(s: String, f2: V => String) = {
        T.traverse(caseKeys) { i => f1(cases(i)).map(v => s"MILL_TEST_${s}_$i" -> f2(v)) }
      }
      def forkEnv = T {
        Map("MILL_TEST_LOGS" -> testLogFolder().toString) ++
          caseEnvs(_.compile)("CLASSES", _.classes.path.toString)() ++
          caseEnvs(_.compileClasspath)("CLASSPATH", _.map(_.path).mkString(","))() ++
          caseEnvs(_.sources)("SOURCES", _.head.path.toString)()
      }

      object cases extends Cross[CaseModule](caseKeys)
      trait CaseModule extends ScalaModule with Cross.Module[String] {
        def caseName = crossValue
        object external extends ScalaModule {
          def scalaVersion = Deps.scalaVersion
        }

        def moduleDeps = Seq(external)

        val Array(prefix, suffix, rest) = caseName.split("-", 3)
        def millSourcePath = super.millSourcePath / prefix / suffix / rest
        def scalaVersion = Deps.scalaVersion
        def ivyDeps = T {
          if (!caseName.contains("realistic") && !caseName.contains("sourcecode")) super.ivyDeps()
          else Agg(
            Deps.fastparse,
            Deps.scalatags,
            Deps.cask,
            Deps.castor,
            Deps.mainargs,
            Deps.requests,
            Deps.osLib,
            Deps.upickle
          )
        }
      }
    }
  }

  object define extends MillStableScalaModule {
    def moduleDeps = Seq(api, util)
    def compileIvyDeps = Agg(Deps.scalaReflect(scalaVersion()))
    def ivyDeps = Agg(
      Deps.millModuledefs,
      // Necessary so we can share the JNA classes throughout the build process
      Deps.jna,
      Deps.jnaPlatform,
      Deps.jarjarabrams,
      Deps.mainargs,
      Deps.scalaparse
    )
  }

  object eval extends MillStableScalaModule {
    def moduleDeps = Seq(define)
  }

  object resolve extends MillStableScalaModule {
    def moduleDeps = Seq(define)
  }

  object client extends MillPublishJavaModule with BuildInfo {
    def buildInfoPackageName = "mill.main.client"
    def buildInfoMembers = Seq(BuildInfo.Value("millVersion", millVersion(), "Mill version."))
    def ivyDeps = Agg(Deps.junixsocket)

    object test extends JavaModuleTests with TestModule.Junit4 {
      def ivyDeps = Agg(
        Deps.junitInterface,
        Deps.lambdaTest,
        Deps.commonsIO
      )
    }
  }

  object server extends MillPublishScalaModule {
    def moduleDeps = Seq(client, api)
  }
  object graphviz extends MillPublishScalaModule {
    def moduleDeps = Seq(main, scalalib)
    def ivyDeps = Agg(Deps.graphvizJava, Deps.jgraphtCore)
  }


  def testModuleDeps = super.testModuleDeps ++ Seq(testkit)
}

object testkit extends MillPublishScalaModule {
  def moduleDeps = Seq(main.eval, main.util, main)
  def ivyDeps = Agg(Deps.TestDeps.utest)

  def sources =
    super.sources() ++
    Seq(PathRef(build.millSourcePath / "mill-build" / "src"))

  def forkEnv = super.forkEnv() ++ Map("MILL_EXECUTABLE_PATH" -> dist.launcher().path.toString())
}

object testrunner extends MillPublishScalaModule {
  def moduleDeps = Seq(scalalib.api, main.util, entrypoint)

  object entrypoint extends MillPublishJavaModule {
    override def ivyDeps = Agg(Deps.sbtTestInterface)
  }
}

def formatDep(dep: Dep) = {
  val d = Lib.depToDependency(dep, Deps.scalaVersion)
  s"${d.module.organization.value}:${d.module.name.value}:${d.version}"
}

object scalalib extends MillStableScalaModule {
  def moduleDeps = Seq(main, scalalib.api, testrunner)
  def ivyDeps = Agg(Deps.scalafmtDynamic, Deps.scalaXml)
  def testIvyDeps = super.testIvyDeps() ++ Agg(Deps.TestDeps.scalaCheck)
  def testTransitiveDeps = super.testTransitiveDeps() ++ Seq(worker.testDep())

  object backgroundwrapper extends MillPublishJavaModule with MillJavaModule {
    def ivyDeps = Agg(Deps.sbtTestInterface)
  }

  object api extends MillStableScalaModule with BuildInfo {
    def moduleDeps = Seq(main.api)
    def buildInfoPackageName = "mill.scalalib.api"
    def buildInfoObjectName = "Versions"

    def buildInfoMembers = Seq(
      BuildInfo.Value("ammonite", Deps.ammoniteVersion, "Version of Ammonite."),
      BuildInfo.Value("zinc", Deps.zinc.dep.version, "Version of Zinc"),
      BuildInfo.Value("scalafmtVersion", Deps.scalafmtDynamic.dep.version, "Version of Scalafmt"),
      BuildInfo.Value("semanticDBVersion", Deps.semanticDBscala.dep.version, "SemanticDB version."),
      BuildInfo.Value(
        "semanticDbJavaVersion",
        Deps.semanticDbJava.dep.version,
        "Java SemanticDB plugin version."
      ),
      BuildInfo.Value(
        "millModuledefsVersion",
        Deps.millModuledefsVersion,
        "Mill ModuleDefs plugins version."
      ),
      BuildInfo.Value("millCompilerBridgeScalaVersions", bridgeScalaVersions.mkString(",")),
      BuildInfo.Value("millCompilerBridgeVersion", bridgeVersion),
      BuildInfo.Value("millVersion", millVersion(), "Mill version."),
      BuildInfo.Value(
        "sbtTestInterface",
        Deps.RuntimeDeps.sbtTestInterface.pipe { d =>
          s"${d.dep.module.organization.value}:${d.dep.module.name.value}:${d.version}"
        },
        "Dependency sbt-test-interface"
      ),
      BuildInfo.Value(
        "jupiterInterface",
        Deps.RuntimeDeps.jupiterInterface.pipe { d =>
          s"${d.dep.module.organization.value}:${d.dep.module.name.value}:${d.version}"
        },
        "Dependency to jupiter-interface"
      )
    )
  }

  object worker extends MillPublishScalaModule with BuildInfo {
    def moduleDeps = Seq(scalalib.api)
    def ivyDeps = Agg(Deps.zinc, Deps.log4j2Core, Deps.scalap(scalaVersion()))
    def buildInfoPackageName = "mill.scalalib.worker"
    def buildInfoObjectName = "Versions"
    def buildInfoMembers = Seq(
      BuildInfo.Value("zinc", Deps.zinc.dep.version, "Version of Zinc.")
    )
  }
}

object scalajslib extends MillStableScalaModule with BuildInfo {
  def moduleDeps = Seq(scalalib, scalajslib.`worker-api`)
  def testTransitiveDeps = super.testTransitiveDeps() ++ Seq(worker("1").testDep())
  def buildInfoPackageName = "mill.scalajslib"
  def buildInfoObjectName = "ScalaJSBuildInfo"

  def buildInfoMembers = T {
    val resolve = resolveCoursierDependency()

    def formatDep(dep: Dep) = {
      val d = resolve(dep)
      s"${d.module.organization.value}:${d.module.name.value}:${d.version}"
    }

    Seq(
      BuildInfo.Value("scalajsEnvNodejs", formatDep(Deps.Scalajs_1.scalajsEnvNodejs)),
      BuildInfo.Value("scalajsEnvJsdomNodejs", formatDep(Deps.Scalajs_1.scalajsEnvJsdomNodejs)),
      BuildInfo.Value(
        "scalajsEnvExoegoJsdomNodejs",
        formatDep(Deps.Scalajs_1.scalajsEnvExoegoJsdomNodejs)
      ),
      BuildInfo.Value("scalajsEnvPhantomJs", formatDep(Deps.Scalajs_1.scalajsEnvPhantomjs)),
      BuildInfo.Value("scalajsEnvSelenium", formatDep(Deps.Scalajs_1.scalajsEnvSelenium)),
      BuildInfo.Value("scalajsImportMap", formatDep(Deps.Scalajs_1.scalajsImportMap))
    )
  }

  object `worker-api` extends MillPublishScalaModule {
    def ivyDeps = Agg(Deps.sbtTestInterface)
  }

  object worker extends Cross[WorkerModule]("1")
  trait WorkerModule extends MillPublishScalaModule with Cross.Module[String] {
    def scalajsWorkerVersion = crossValue
    def millSourcePath: os.Path = super.millSourcePath / scalajsWorkerVersion
    def testDepPaths = T { Seq(compile().classes) }
    def moduleDeps = Seq(scalajslib.`worker-api`, main.client, main.api)
    def ivyDeps = Agg(
      Deps.Scalajs_1.scalajsLinker,
      Deps.Scalajs_1.scalajsSbtTestAdapter,
      Deps.Scalajs_1.scalajsEnvNodejs,
      Deps.Scalajs_1.scalajsEnvJsdomNodejs,
      Deps.Scalajs_1.scalajsEnvExoegoJsdomNodejs,
      Deps.Scalajs_1.scalajsEnvPhantomjs,
      Deps.Scalajs_1.scalajsEnvSelenium,
      Deps.Scalajs_1.scalajsImportMap
    )
  }
}

object contrib extends Module {
  def contribModules: Seq[ContribModule] =
    millInternal.modules.collect { case m: ContribModule => m }

  trait ContribModule extends MillPublishScalaModule {
    def readme = T.source(millSourcePath / "readme.adoc")
  }

  object testng extends JavaModule with ContribModule {

    def testTransitiveDeps =
      super.testTransitiveDeps() ++
        Seq(scalalib.testDep(), scalalib.worker.testDep(), testrunner.entrypoint.testDep())

    // pure Java implementation
    def artifactSuffix: T[String] = ""
    def scalaLibraryIvyDeps: T[Agg[Dep]] = T { Agg.empty[Dep] }
    def ivyDeps = Agg(Deps.sbtTestInterface)
    def compileIvyDeps = Agg(Deps.testng)
    def runIvyDeps = Agg(Deps.testng)
    def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
    def docJar: T[PathRef] = super[JavaModule].docJar
  }

  object twirllib extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object playlib extends ContribModule {
    def moduleDeps = Seq(twirllib, playlib.api)
    def compileModuleDeps = Seq(scalalib)

    def testTransitiveDeps =
      super.testTransitiveDeps() ++ T.traverse(Deps.play.keys.toSeq)(worker(_).testDep)()

    def testArgs = T {
      super.testArgs() ++
        Seq(
          s"-DTEST_PLAY_VERSION_2_6=${Deps.Play_2_6.playVersion}",
          s"-DTEST_PLAY_VERSION_2_7=${Deps.Play_2_7.playVersion}",
          s"-DTEST_PLAY_VERSION_2_8=${Deps.Play_2_8.playVersion}",
          s"-DTEST_PLAY_VERSION_2_9=${Deps.Play_2_9.playVersion}",
          s"-DTEST_PLAY_VERSION_3_0=${Deps.Play_3_0.playVersion}"
        )
    }

    def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)

    object api extends MillPublishJavaModule

    object worker extends Cross[WorkerModule](Deps.play.keys.toSeq)
    trait WorkerModule extends MillPublishScalaModule with Cross.Module[String] {
      def playBinary = crossValue
      def millSourcePath: os.Path = super.millSourcePath / playBinary

      def sources = T.sources {
        // We want to avoid duplicating code as long as the Play APIs allow.
        // But if newer Play versions introduce incompatibilities,
        // just remove the shared source dir for that worker and implement directly.
        Seq(PathRef(millSourcePath / os.up / "src-shared")) ++ super.sources()
      }

      def scalaVersion = Deps.play(playBinary).scalaVersion
      def moduleDeps = Seq(playlib.api)
      def ivyDeps = Agg(Deps.osLib, Deps.play(playBinary).routesCompiler)
    }
  }

  object scalapblib extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib)
  }

  object scoverage extends ContribModule {
    object api extends MillPublishScalaModule {
      def compileModuleDeps = Seq(main.api)
    }

    def moduleDeps = Seq(scoverage.api)
    def compileModuleDeps = Seq(scalalib)

    def testTransitiveDeps =
      super.testTransitiveDeps() ++ Seq(worker.testDep(), worker2.testDep())

    def testArgs = T {
      super.testArgs() ++
        Seq(
          s"-DMILL_SCOVERAGE_VERSION=${Deps.scalacScoveragePlugin.dep.version}",
          s"-DMILL_SCOVERAGE2_VERSION=${Deps.scalacScoverage2Plugin.dep.version}",
          s"-DTEST_SCALA_2_13_VERSION_FOR_SCOVERAGE_1=${Deps.scalaVersionForScoverageWorker1}",
          s"-DTEST_SCALA_2_12_VERSION=2.12.15" // last supported 2.12 version for Scoverage 1.x
        )
    }

    // So we can test with buildinfo in the classpath
    def testModuleDeps =
      super.testModuleDeps ++
        Seq(scalalib, scalajslib, scalanativelib, contrib.buildinfo)

    // Worker for Scoverage 1.x
    object worker extends MillPublishScalaModule {
      // scoverage is on an old Scala version which doesnt support scalafix
      def fix(args: String*): Command[Unit] = T.command {}
      def compileModuleDeps = Seq(main.api)
      def moduleDeps = Seq(scoverage.api)
      def testDepPaths = T { Seq(compile().classes) }

      // compile-time only, need to provide the correct scoverage version at runtime
      def compileIvyDeps = Agg(Deps.scalacScoveragePlugin)
      def scalaVersion = Deps.scalaVersionForScoverageWorker1
    }

    // Worker for Scoverage 2.0
    object worker2 extends MillPublishScalaModule {
      def compileModuleDeps = Seq(main.api)
      def moduleDeps = Seq(scoverage.api)
      def testDepPaths = T { Seq(compile().classes) }
      def compileIvyDeps = T {
        Agg(
          // compile-time only, need to provide the correct scoverage version at runtime
          Deps.scalacScoverage2Plugin,
          Deps.scalacScoverage2Reporter,
          Deps.scalacScoverage2Domain,
          Deps.scalacScoverage2Serializer
        )
      }
    }
  }

  object buildinfo extends ContribModule {
    def compileModuleDeps = Seq(scalalib, scalajslib, scalanativelib)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib, scalajslib, scalanativelib)
  }

  object proguard extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib)
  }

  object flyway extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.flywayCore)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib)
  }

  object docker extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib)
  }

  object bloop extends ContribModule with BuildInfo {
    def compileModuleDeps = Seq(scalalib, scalajslib, scalanativelib)
    def ivyDeps = Agg(Deps.bloopConfig.exclude("*" -> s"jsoniter-scala-core_2.13"))
    def testModuleDeps = super.testModuleDeps ++ Seq(
      scalalib,
      scalajslib,
      scalanativelib
    )

    def buildInfoPackageName = "mill.contrib.bloop"
    def buildInfoObjectName = "Versions"
    def buildInfoMembers = Seq(BuildInfo.Value("bloop", Deps.bloopConfig.dep.version))
  }

  object artifactory extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.requests)
  }

  object codeartifact extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.requests)
  }

  object sonatypecentral extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.sonatypeCentralClient)
  }

  object versionfile extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
  }

  object bintray extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.requests)
  }

  object gitlab extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.requests, Deps.osLib)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib)
  }

  object jmh extends ContribModule {
    def compileModuleDeps = Seq(scalalib)
    def testModuleDeps = super.testModuleDeps ++ Seq(scalalib)
  }
}

object scalanativelib extends MillStableScalaModule {
  def moduleDeps = Seq(scalalib, scalanativelib.`worker-api`)
  def testTransitiveDeps =
    super.testTransitiveDeps() ++ Seq(worker("0.5").testDep())

  object `worker-api` extends MillPublishScalaModule {
    def ivyDeps = Agg(Deps.sbtTestInterface)
  }

  object worker extends Cross[WorkerModule]("0.5")

  trait WorkerModule extends MillPublishScalaModule with Cross.Module[String] {
    def scalaNativeWorkerVersion = crossValue
    def millSourcePath: os.Path = super.millSourcePath / scalaNativeWorkerVersion
    def testDepPaths = T { Seq(compile().classes) }
    def moduleDeps = Seq(scalanativelib.`worker-api`)
    def ivyDeps = scalaNativeWorkerVersion match {
      case "0.5" =>
        Agg(
          Deps.osLib,
          Deps.Scalanative_0_5.scalanativeTools,
          Deps.Scalanative_0_5.scalanativeUtil,
          Deps.Scalanative_0_5.scalanativeNir,
          Deps.Scalanative_0_5.scalanativeTestRunner
        )
    }
  }
}

object bsp extends MillPublishScalaModule with BuildInfo {
  def compileModuleDeps = Seq(scalalib)
  def testModuleDeps = super.testModuleDeps ++ compileModuleDeps
  def buildInfoPackageName = "mill.bsp"

  def buildInfoMembers = T {
    val workerDep = worker.publishSelfDependency()
    Seq(
      BuildInfo.Value(
        "bsp4jVersion",
        Deps.bsp4j.dep.version,
        "BSP4j version (BSP Protocol version)."
      )
    )
  }

  override lazy val test: MillScalaTests = new Test {}
  trait Test extends MillScalaTests {
    def forkEnv: T[Map[String, String]] = T {
      // We try to fetch this dependency with coursier in the tests
      bsp.worker.publishLocalCached()
      super.forkEnv()
    }

    def forkArgs = super.forkArgs() ++ Seq(s"-DBSP4J_VERSION=${Deps.bsp4j.dep.version}")
  }

  object worker extends MillPublishScalaModule {
    def compileModuleDeps = Seq(bsp, scalalib, testrunner, runner) ++ scalalib.compileModuleDeps
    def ivyDeps = Agg(Deps.bsp4j, Deps.sbtTestInterface)
  }
}

val DefaultLocalMillReleasePath =
  s"target/mill-release${if (scala.util.Properties.isWin) ".bat" else ""}"

// We compile the test code once and then offer multiple modes to
// test it in the `test` CrossModule. We pass `test`'s sources to `lib` to
// and pass `lib`'s compile output back to `test`
trait IntegrationTestModule extends MillScalaModule {
  def repoSlug: String

  def moduleDeps = Seq(main.test, testkit, runner)
  def sources = T.sources(millSourcePath / "test" / "src")
  def testRepoRoot: T[PathRef] = T.source(millSourcePath / "repo")

  trait ModeModule extends ScalaModule with MillBaseTestsModule {
    def mode: String = millModuleSegments.parts.last
    def scalaVersion = Deps.scalaVersion

    def forkEnv =
      super.forkEnv() ++
        IntegrationTestModule.this.forkEnv() ++
        Map(
          "MILL_INTEGRATION_SERVER_MODE" -> (mode == "local" || mode == "server").toString,
          "MILL_INTEGRATION_REPO_ROOT" -> testRepoRoot().path.toString
        ) ++
        testReleaseEnv()

    def forkArgs = T { super.forkArgs() ++ dist.forkArgs() }

    def testReleaseEnv =
      if (mode == "local") T { Map("MILL_INTEGRATION_LAUNCHER" -> dist.launcher().path.toString()) }
      else T { Map("MILL_INTEGRATION_LAUNCHER" -> integration.testMill().path.toString()) }

    def compile = IntegrationTestModule.this.compile()
    def moduleDeps = Seq(IntegrationTestModule.this)
  }
}

trait IntegrationTestCrossModule extends IntegrationTestModule with Cross.Module[String] {
  def repoSlug = crossValue
  def millSourcePath = super.millSourcePath / repoSlug

  object local extends ModeModule
  object fork extends ModeModule
  object server extends ModeModule
}

def listIn(path: os.Path) = interp.watchValue(os.list(path).map(_.last))

object example extends Module {
  def exampleModules: Seq[ExampleCrossModule] = millInternal
    .modules
    .collect { case m: ExampleCrossModule => m }


  object javalib extends Module{
    object basic extends Cross[ExampleCrossModuleJava](listIn(millSourcePath / "basic"))
    object builds extends Cross[ExampleCrossModuleJava](listIn(millSourcePath / "builds"))
    object testing extends Cross[ExampleCrossModuleJava](listIn(millSourcePath / "testing"))
    object module extends Cross[ExampleCrossModuleJava](listIn(millSourcePath / "module"))
    object web extends Cross[ExampleCrossModule](listIn(millSourcePath / "web"))
  }
  object scalalib extends Module{
    object basic extends Cross[ExampleCrossModule](listIn(millSourcePath / "basic"))
    object builds extends Cross[ExampleCrossModule](listIn(millSourcePath / "builds"))
    object testing extends Cross[ExampleCrossModule](listIn(millSourcePath / "testing"))
    object module extends Cross[ExampleCrossModule](listIn(millSourcePath / "module"))
    object web extends Cross[ExampleCrossModule](listIn(millSourcePath / "web"))
  }

  object depth extends Module{
    object tasks extends Cross[ExampleCrossModule](listIn(millSourcePath / "tasks"))
    object modules extends Cross[ExampleCrossModule](listIn(millSourcePath / "modules"))
    object cross extends Cross[ExampleCrossModule](listIn(millSourcePath / "cross"))
  }

  object extending extends Module{
    object imports extends Cross[ExampleCrossModule](listIn(millSourcePath / "imports"))
    object metabuild extends Cross[ExampleCrossModule](listIn(millSourcePath / "metabuild"))
    object plugins extends Cross[ExampleCrossModule](listIn(millSourcePath / "plugins"))
  }

  trait ExampleCrossModuleJava extends ExampleCrossModule {

    def upstreamCross(s: String) = s match {
      case "basic" => scalalib.basic
      case "builds" => scalalib.builds
      case "module" => scalalib.module
      case "testing" => scalalib.testing
      case "web" => scalalib.web
    }
    def testRepoRoot = T{
      os.copy.over(super.testRepoRoot().path, T.dest)
      for(lines <- buildScLines()) os.write.over(T.dest / "build.sc", lines.mkString("\n"))
      PathRef(T.dest)
    }
    def buildScLines =
      upstreamCross(
        this.millModuleSegments.parts.dropRight(1).last
      ).valuesToModules.get(List(crossValue)) match {
        case None => T {None}
        case Some(upstream) => T {
          Some {
            val upstreamLines = os.read.lines(upstream.testRepoRoot().path / "build.sc")
            val lines = os.read.lines(super.testRepoRoot().path / "build.sc")

            import collection.mutable
            val groupedLines = mutable.Map.empty[String, mutable.Buffer[String]]
            var current = Option.empty[String]
            lines.foreach {
              case s"//// SNIPPET:$name" =>
                current = Some(name)
                groupedLines(name) = mutable.Buffer()
              case s => current.foreach(groupedLines(_).append(s))
            }

            current = None
            upstreamLines.flatMap {
              case s"//// SNIPPET:$name" =>
                if (name != "END") {
                  current = Some(name)
                  groupedLines(name)
                } else {
                  current = None
                  Nil
                }

              case s => if (current.nonEmpty) None else Some(s)
            }
          }
        }
      }
  }

  trait ExampleCrossModule extends IntegrationTestCrossModule {
    // disable scalafix because these example modules don't have sources causing it to misbehave
    def fix(args: String*): Command[Unit] = T.command {}
    def testRepoRoot: T[PathRef] = T.source(millSourcePath)

    def compile = testkit.compile()

    def forkEnv = super.forkEnv() ++ Map(
      "MILL_EXAMPLE_PARSED" -> upickle.default.write(parsed()),
      "LANG" -> "C"
    )

    /**
     * Parses a `build.sc` for specific comments and return the split-by-type content
     */
    def parsed: T[Seq[(String, String)]] = T {
      mill.testkit.ExampleParser(testRepoRoot().path)
    }

    def rendered = T {
      var seenCode = false
      val examplePath = millSourcePath.subRelativeTo(T.workspace)
      os.write(
        T.dest / "example.adoc",
        parsed()
          .filter(_._2.nonEmpty)
          .map {
            case (s"see:$path", txt) =>
              s"""
                 |.$path ({mill-example-url}/$examplePath/$path[browse])
                 |[source,scala,subs="attributes,verbatim"]
                 |----
                 |$txt
                 |----""".stripMargin
            case ("scala", txt) =>
              val title =
                if (seenCode) ""
                else {
                  val label = millLastTag()
                  val exampleDashed = examplePath.segments.mkString("-")
                  val download = s"{mill-download-url}/$label-$exampleDashed.zip[download]"
                  val browse = s"{mill-example-url}/$examplePath[browse]"
                  s".build.sc ($download, $browse)"
                }
              seenCode = true
              s"""
                 |$title
                 |[source,scala,subs="attributes,verbatim"]
                 |----
                 |
                 |$txt
                 |----
                 |""".stripMargin
            case ("comment", txt) => txt + "\n"
            case ("example", txt) =>
              s"""
                 |[source,bash,subs="attributes,verbatim"]
                 |----
                 |$txt
                 |----""".stripMargin
          }
          .mkString("\n")
      )
      PathRef(T.dest / "example.adoc")
    }
  }

  def repoInfo = Map(
    "acyclic" -> ("com-lihaoyi/acyclic", "1ec221f377794db39e8ff9b43415f81c703c202f"),
    "fansi" -> ("com-lihaoyi/fansi", "169ac96d7c6761a72590d312a433cf12c572573c"),
    "jimfs" -> ("google/jimfs", "5b60a42eb9d3cd7a2073d549bd0cb833f5a7e7e9"),
    "commons-io" -> ("apache/commons-io", "b91a48074231ef813bc9b91a815d77f6343ff8f0"),
    "netty" -> ("netty/netty", "20a790ed362a3c11e0e990b58598e4ac6aa88bef"),
    "mockito" -> ("mockito/mockito", "97f3574cc07fdf36f1f76ba7332ac57675e140b1"),
    "gatling" -> ("gatling/gatling", "3870fda86e6bca005fbd53108c60a65db36279b6")
  )
  object thirdparty extends Cross[ThirdPartyModule](listIn(millSourcePath / "thirdparty"))
  trait ThirdPartyModule extends ExampleCrossModule {
    val (repoPath, repoHash) = repoInfo(crossValue)
    def repoSlug = repoPath.split("/").last

    def testRepoRoot = T {
      shared.downloadTestRepo(repoPath, repoHash, T.dest)
      val wrapperFolder = T.dest / s"$repoSlug-$repoHash"

      os.makeDir(T.dest / "merged")
      os.copy(wrapperFolder, T.dest / "merged", mergeFolders = true)
      os.remove.all(wrapperFolder)
      os.copy(
        super.testRepoRoot().path,
        T.dest / "merged",
        mergeFolders = true,
        replaceExisting = true
      )
      os.remove.all(T.dest / "merged" / ".mill-version")

      PathRef(T.dest / "merged")
    }
  }
}

object integration extends Module {
  object failure extends Cross[IntegrationCrossModule](listIn(millSourcePath / "failure"))
  object feature extends Cross[IntegrationCrossModule](listIn(millSourcePath / "feature"))
  object invalidation extends Cross[IntegrationCrossModule](listIn(millSourcePath / "invalidation"))
  object ide extends Cross[IntegrationCrossModule](listIn(millSourcePath / "ide"))
  trait IntegrationCrossModule extends IntegrationTestCrossModule

  /** Deploy freshly build mill for use in tests */
  def testMill: T[PathRef] = {
    val name = if (scala.util.Properties.isWin) "mill.bat" else "mill"
    T { PathRef(installLocalTask(binFile = T.task((T.dest / name).toString()))()) }
  }
}

def launcherScript(
    shellJvmArgs: Seq[String],
    cmdJvmArgs: Seq[String],
    shellClassPath: Agg[String],
    cmdClassPath: Agg[String]
) = {

  val millMainClass = "mill.runner.client.MillClientMain"

  Jvm.universalScript(
    shellCommands = {
      val jvmArgsStr = shellJvmArgs.mkString(" ")
      def java(mainClass: String, passMillJvmOpts: Boolean) = {
        val millJvmOpts = if (passMillJvmOpts) "$mill_jvm_opts" else ""
        s"""exec "$$JAVACMD" $jvmArgsStr $$JAVA_OPTS $millJvmOpts -cp "${shellClassPath.mkString(
            ":"
          )}" $mainClass "$$@""""
      }

      s"""if [ -z "$$JAVA_HOME" ] ; then
         |  JAVACMD="java"
         |else
         |  JAVACMD="$$JAVA_HOME/bin/java"
         |fi
         |
         |mill_jvm_opts=""
         |init_mill_jvm_opts () {
         |  if [ -z $$MILL_JVM_OPTS_PATH ] ; then
         |    mill_jvm_opts_file=".mill-jvm-opts"
         |  else
         |    mill_jvm_opts_file=$$MILL_JVM_OPTS_PATH
         |  fi
         |
         |  if [ -f "$$mill_jvm_opts_file" ] ; then
         |    # We need to append a newline at the end to fix
         |    # https://github.com/com-lihaoyi/mill/issues/2140
         |    newline="
         |"
         |    mill_jvm_opts="$$(
         |      echo "$$newline" | cat "$$mill_jvm_opts_file" - | (
         |        while IFS= read line
         |        do
         |          mill_jvm_opts="$${mill_jvm_opts} $$(echo $$line | grep -v "^[[:space:]]*[#]")"
         |        done
         |        # we are in a sub-shell, so need to return it explicitly
         |        echo "$${mill_jvm_opts}"
         |      )
         |    )"
         |    mill_jvm_opts="$${mill_jvm_opts} -Dmill.jvm_opts_applied=true"
         |  fi
         |}
         |
         |# Client-server mode doesn't seem to work on WSL, just disable it for now
         |# https://stackoverflow.com/a/43618657/871202
         |if grep -qEi "(Microsoft|WSL)" /proc/version > /dev/null 2> /dev/null ; then
         |    init_mill_jvm_opts
         |    if [ -z $$COURSIER_CACHE ] ; then
         |      COURSIER_CACHE=.coursier
         |    fi
         |    ${java(millMainClass, true)}
         |else
         |    if [ "$${1%"-i"*}" != "$$1" ] ; then # first arg starts with "-i"
         |        init_mill_jvm_opts
         |        ${java(millMainClass, true)}
         |    else
         |        case "$$1" in
         |          -i | --interactive | --repl | --no-server | --bsp )
         |            init_mill_jvm_opts
         |            ${java(millMainClass, true)}
         |            ;;
         |          *)
         |            ${java(millMainClass, false)}
         |            ;;
         |        esac
         |    fi
         |fi
         |""".stripMargin
    },
    cmdCommands = {
      val jvmArgsStr = cmdJvmArgs.mkString(" ")
      def java(mainClass: String, passMillJvmOpts: Boolean) = {
        val millJvmOpts = if (passMillJvmOpts) "!mill_jvm_opts!" else ""
        s""""%JAVACMD%" $jvmArgsStr %JAVA_OPTS% $millJvmOpts -cp "${cmdClassPath.mkString(
            ";"
          )}" $mainClass %*"""
      }

      s"""setlocal EnableDelayedExpansion
         |set "JAVACMD=java.exe"
         |if not "%JAVA_HOME%"=="" set "JAVACMD=%JAVA_HOME%\\bin\\java.exe"
         |if "%1" == "-i" set _I_=true
         |if "%1" == "--interactive" set _I_=true
         |if "%1" == "--repl" set _I_=true
         |if "%1" == "--no-server" set _I_=true
         |if "%1" == "--bsp" set _I_=true
         |
         |set "mill_jvm_opts="
         |set "mill_jvm_opts_file=.mill-jvm-opts"
         |if not "%MILL_JVM_OPTS_PATH%"=="" set "mill_jvm_opts_file=%MILL_JVM_OPTS_PATH%"
         |
         |if defined _I_ (
         |  if exist %mill_jvm_opts_file% (
         |    for /f "delims=" %%G in (%mill_jvm_opts_file%) do (
         |      set line=%%G
         |      if "!line:~0,2!"=="-X" set "mill_jvm_opts=!mill_jvm_opts! !line!"
         |    )
         |  )
         |  ${java(millMainClass, true)}
         |) else (
         |  ${java(millMainClass, false)}
         |)
         |endlocal
         |""".stripMargin
    }
  )
}

object runner extends MillPublishScalaModule {
  object client extends MillPublishJavaModule{
    def buildInfoPackageName = "mill.runner.client"
    def moduleDeps = Seq(main.client)
  }

  def moduleDeps = Seq(
    scalalib, scalajslib, scalanativelib, bsp, linenumbers, main.codesig, main.server, client
  )
  def skipPreviousVersions: T[Seq[String]] = Seq("0.11.0-M7")

  object linenumbers extends MillPublishScalaModule {
    def scalaVersion = Deps.scalaVersion
    def ivyDeps = Agg(Deps.scalaCompiler(scalaVersion()))
  }
}

object idea extends MillPublishScalaModule {
  def moduleDeps = Seq(scalalib, runner)
}

/**
 * Version of [[dist]] meant for local integration testing within the Mill
 * repo. Looks mostly the same as [[dist]], except it does not have a reference
 * to itself in its [[testTransitiveDeps]], to avoid a circular dependency.
 */
object dist0 extends MillPublishJavaModule {
  // disable scalafix here because it crashes when a module has no sources
  def fix(args: String*): Command[Unit] = T.command {}
  def moduleDeps = Seq(runner, idea)

  def testTransitiveDeps = runner.testTransitiveDeps() ++ Seq(
    main.graphviz.testDep(),
    runner.linenumbers.testDep(),
    scalalib.backgroundwrapper.testDep(),
    contrib.bloop.testDep(),
    contrib.buildinfo.testDep(),
    contrib.scoverage.testDep(),
    contrib.scoverage.worker2.testDep(),
    contrib.jmh.testDep(),
    contrib.playlib.testDep(),
    contrib.playlib.worker("2.8").testDep(),
    bsp.worker.testDep(),
    testkit.testDep(),
  )
}


object dist extends MillPublishJavaModule {
  def jar = rawAssembly()
  def moduleDeps = Seq(runner, idea)

  def testTransitiveDeps = dist0.testTransitiveDeps() ++ Seq(
    (s"com.lihaoyi-${dist.artifactId()}", dist0.runClasspath().map(_.path).mkString("\n")),
  )

  def genTask(m: ScalaModule) = T.task { Seq(m.jar(), m.sourceJar()) ++ m.runClasspath() }

  def forkArgs: T[Seq[String]] = T {
    val genIdeaArgs =
      genTask(main.define)() ++
        genTask(main.eval)() ++
        genTask(main)() ++
        genTask(scalalib)() ++
        genTask(scalajslib)() ++
        genTask(scalanativelib)()

    testArgs() ++
      Seq(
        "-DMILL_CLASSPATH=" + runClasspath().map(_.path.toString).mkString(","),
        "-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","),
        s"-DBSP4J_VERSION=${Deps.bsp4j.dep.version}"
      )
  }

  def launcher = T {
    val isWin = scala.util.Properties.isWin
    val outputPath = T.dest / (if (isWin) "run.bat" else "run")

    os.write(outputPath, prependShellScript())
    if (!isWin) os.perms.set(outputPath, "rwxrwxrwx")

    PathRef(outputPath)
  }

  def extraPublish: T[Seq[PublishInfo]] = T {
    Seq(PublishInfo(file = assembly(), classifier = Some("assembly"), ivyConfig = "compile"))
  }

  def assemblyRules = super.assemblyRules ++ Seq(
    mill.scalalib.Assembly.Rule.ExcludePattern("mill/local-test-overrides/.*")
  )

  // All modules that we want to aggregate as part of this `dev` assembly.
  // Excluding itself, and the `dist` module that uses it
  lazy val allPublishModules = build.millInternal.modules.collect {
    case m: PublishModule if (m ne this) && (m ne dist) => m
  }

  def rawAssembly = T{
    val version = millVersion()
    val devRunClasspath = runClasspath().map(_.path)
    val filename = if (scala.util.Properties.isWin) "mill.bat" else "mill"
    val commonArgs = Seq(
      // Workaround for Zinc/JNA bug
      // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
      "-Djna.nosys=true"
    )
    val shellArgs = Seq("-DMILL_CLASSPATH=$0") ++ commonArgs
    val cmdArgs = Seq(""""-DMILL_CLASSPATH=%~dpnx0"""") ++ commonArgs
    os.move(
      mill.scalalib.Assembly.createAssembly(
        devRunClasspath,
        prependShellScript = launcherScript(shellArgs, cmdArgs, Agg("$0"), Agg("%~dpnx0")),
        assemblyRules = assemblyRules
      ).path,
      T.dest / filename
    )
    PathRef(T.dest / filename)
  }
  def assembly = T {
    T.traverse(allPublishModules)(m => m.publishLocalCached)()
    val raw = rawAssembly().path
    os.copy(raw, T.dest / raw.last)
    PathRef(T.dest / raw.last)
  }

  def prependShellScript = T {
    val (millArgs, otherArgs) =
      forkArgs().partition(arg => arg.startsWith("-DMILL") && !arg.startsWith("-DMILL_VERSION"))
    // Pass Mill options via file, due to small max args limit in Windows
    val vmOptionsFile = T.dest / "mill.properties"
    val millOptionsContent =
      millArgs.map(_.drop(2).replace("\\", "/")).mkString(
        "\r\n"
      ) // drop -D prefix, replace \ with /
    os.write(vmOptionsFile, millOptionsContent)
    val jvmArgs = otherArgs ++ List(s"-DMILL_OPTIONS_PATH=$vmOptionsFile")
    val classpath = runClasspath().map(_.path.toString)
    launcherScript(
      jvmArgs,
      jvmArgs,
      classpath,
      Agg(pathingJar().path.toString) // TODO not working yet on Windows! see #791
    )
  }

  def pathingJar = T {
    // see http://todayguesswhat.blogspot.com/2011/03/jar-manifestmf-class-path-referencing.html
    // for more detailed explanation
    val isWin = scala.util.Properties.isWin
    val classpath = runClasspath().map { pathRef =>
      val path =
        if (isWin) "/" + pathRef.path.toString.replace("\\", "/")
        else pathRef.path.toString
      if (path.endsWith(".jar")) path
      else path + "/"
    }.mkString(" ")
    val manifestEntries = Map[String, String](
      java.util.jar.Attributes.Name.MANIFEST_VERSION.toString -> "1.0",
      "Created-By" -> "Scala mill",
      "Class-Path" -> classpath
    )
    Jvm.createJar(Agg(), JarManifest(manifestEntries))
  }

  def run(args: Task[Args] = T.task(Args())) = T.command {
    args().value match {
      case Nil => mill.api.Result.Failure("Need to pass in cwd as first argument to dev.run")
      case wd0 +: rest =>
        val wd = os.Path(wd0, T.workspace)
        os.makeDir.all(wd)
        try {
          Jvm.runSubprocess(
            Seq(launcher().path.toString) ++ rest,
            forkEnv(),
            workingDir = wd
          )
          mill.api.Result.Success(())
        } catch {
          case e: Throwable =>
            mill.api.Result.Failure(s"dev.run failed with an exception. ${e.getMessage()}")
        }
    }
  }
}

/** Generates the mill documentation with Antora. */
object docs extends Module {
  // This module isn't really a ScalaModule, but we use it to generate
  // consolidated documentation using the Scaladoc tool.
  object site extends UnidocModule {
    def scalaVersion = Deps.scalaVersion
    def moduleDeps = build.millInternal.modules.collect { case m: MillStableScalaModule => m }
    def unidocSourceUrl = T {
      val sha = VcsVersion.vcsState().currentRevision
      Some(s"${Settings.projectUrl}/blob/$sha")
    }
  }

  private val npmExe = if (scala.util.Properties.isWin) "npm.cmd" else "npm"
  private val antoraExe = if (scala.util.Properties.isWin) "antora.cmd" else "antora"
  def npmBase: T[os.Path] = T.persistent { T.dest }
  def prepareAntora(npmDir: os.Path) = {
    Jvm.runSubprocess(
      commandArgs = Seq(
        npmExe,
        "install",
        "@antora/cli@3.1.9",
        "@antora/site-generator-default@3.1.9",
        "gitlab:antora/xref-validator",
        "@antora/lunr-extension@v1.0.0-alpha.6",
        "asciidoctor-kroki@0.18.1"
      ),
      envArgs = Map(),
      workingDir = npmDir
    )
  }

  def runAntora(npmDir: os.Path, workDir: os.Path, args: Seq[String])(implicit
      ctx: mill.api.Ctx.Log
  ) = {

    prepareAntora(npmDir)
    val cmdArgs =
      Seq(s"${npmDir}/node_modules/.bin/${antoraExe}") ++ args
    ctx.log.debug(s"command: ${cmdArgs.mkString("'", "' '", "'")}")
    Jvm.runSubprocess(
      commandArgs = cmdArgs,
      envArgs = Map("CI" -> "true"),
      workingDir = workDir
    )
    PathRef(workDir / "build" / "site")
  }

  def source0 = T.source(millSourcePath)
  def projectReadme = T.source(T.workspace / "readme.adoc")
  def source = T {
    os.copy(source0().path, T.dest, mergeFolders = true)

    val pagesWd = T.dest / "modules" / "ROOT" / "pages"
    val partialsWd = T.dest / "modules" / "ROOT" / "partials"

    os.copy(projectReadme().path, partialsWd / "project-readme.adoc", createFolders = true)

    val renderedExamples: Seq[(os.SubPath, PathRef)] =
      T.traverse(example.exampleModules)(m =>
        T.task {
          (m.millSourcePath.subRelativeTo(example.millSourcePath), m.rendered())
        }
      )()

    for ((name, pref) <- renderedExamples) os.copy(
      pref.path,
      pagesWd / "example" / os.SubPath(s"$name.adoc"),
      createFolders = true
    )

    val contribReadmes = T.traverse(contrib.contribModules)(m =>
      T.task {
        m.millModuleSegments.parts.last -> m.readme()
      }
    )()

    for ((name, pref) <- contribReadmes) os.copy(
      pref.path,
      pagesWd / "contrib" / s"${name}.adoc",
      createFolders = true
    )

    PathRef(T.dest)
  }

  def supplementalFiles = T.source(millSourcePath / "supplemental-ui")

  /**
   * The doc root ready to be build by antora for the current branch.
   */
  def devAntoraSources: T[PathRef] = T {
    val dest = T.dest
    os.copy(source().path, dest, mergeFolders = true)
    sanitizeAntoraYml(dest, "master", millVersion(), millLastTag())
    PathRef(dest)
  }

  def sanitizeAntoraYml(
      dest: os.Path,
      version: String,
      millVersion: String,
      millLastTag: String
  ): Unit = {
    val isPreRelease = (millVersion != millLastTag) || Seq("-M", "-RC").exists(millVersion.contains)
    val lines = os.read(dest / "antora.yml").linesIterator.map {
      case s"version:$_" =>
        if (isPreRelease)
          s"version: '${version}'\ndisplay-version: '${millVersion}'\nprerelease: true"
        else
          s"version: '${version}'\ndisplay-version: '${millVersion}'"
      case s"    mill-version:$_" => s"    mill-version: '$millVersion'"
      case s"    mill-last-tag:$_" => s"    mill-last-tag: '$millLastTag'"
      case l => l
    }
    val newLines = Seq(
      s"    mill-download-url: ${Settings.projectUrl}/releases/download/$millLastTag",
      s"    mill-example-url: ${Settings.projectUrl}/blob/$millLastTag/",
    )

    os.write.over(dest / "antora.yml", (lines ++ newLines).mkString("\n"))
  }

  def githubPagesPlaybookText(authorMode: Boolean) = T.task { extraSources: Seq[os.Path] =>
    val taggedSources = for (path <- extraSources) yield {
      s"""    - url: ${baseDir}
         |      start_path: ${path.relativeTo(baseDir)}
         |""".stripMargin
    }
    s"""site:
       |  title: The Mill Build Tool
       |  url: ${if (authorMode) s"${T.dest}/site" else Settings.docUrl}
       |  start_page: mill::Java_Intro_to_Mill.adoc
       |  keys:
       |    google_analytics: 'G-1C582ZJR85'
       |
       |content:
       |  sources:
       |    - url: ${if (authorMode) baseDir else Settings.projectUrl}
       |      branches: []
       |      tags: ${Settings.legacyDocTags.map("'" + _ + "'").mkString("[", ",", "]")}
       |      start_path: docs/antora
       |
       |${taggedSources.mkString("\n\n")}
       |
       |    - url: ${baseDir}
       |      start_path: ${devAntoraSources().path.relativeTo(baseDir)}
       |ui:
       |  bundle:
       |    url: https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/master/raw/build/ui-bundle.zip?job=bundle-stable
       |    snapshot: true
       |  supplemental_files: ${supplementalFiles().path.toString()}
       |
       |asciidoc:
       |  attributes:
       |    mill-github-url: ${Settings.projectUrl}
       |    mill-doc-url: ${if (authorMode) s"file://${T.dest}/site" else Settings.docUrl}
       |    utest-github-url: https://github.com/com-lihaoyi/utest
       |    upickle-github-url: https://github.com/com-lihaoyi/upickle
       |    mill-scip-version: ${Deps.DocDeps.millScip.dep.version}
       |    kroki-fetch-diagram: true
       |  extensions:
       |  - asciidoctor-kroki
       |antora:
       |  extensions:
       |  - require: '@antora/lunr-extension'
       |    index_latest_only: true
       |
       |runtime:
       |  log:
       |    failure_level: error
       |
       |""".stripMargin
  }

  def oldDocSources = T {
    for (oldVersion <- Settings.docTags) yield {
      val checkout = T.dest / oldVersion
      os.proc("git", "clone", T.workspace / ".git", checkout).call(stdout = os.Inherit)
      os.proc("git", "checkout", oldVersion).call(cwd = checkout, stdout = os.Inherit)
      val outputFolder = checkout / "out" / "docs" / "source.dest"
      os.proc("./mill", "-i", "docs.source").call(cwd = checkout, stdout = os.Inherit)
      sanitizeAntoraYml(outputFolder, oldVersion, oldVersion, oldVersion)
      PathRef(outputFolder)
    }
  }

  def githubPages: T[PathRef] = T {
    generatePages(authorMode = false)().apply(oldDocSources().map(_.path))
  }

  def localPages = T {
    val pages = generatePages(authorMode = true)().apply(oldDocSources().map(_.path))
    T.log.outputStream.println(
      s"You can browse the local pages at: ${(pages.path / "index.html").toNIO.toUri()}"
    )
  }
  def fastPages = T {
    val pages = generatePages(authorMode = true)().apply(Nil)
    T.log.outputStream.println(
      s"You can browse the local pages at: ${(pages.path / "index.html").toNIO.toUri()}"
    )
  }

  def generatePages(authorMode: Boolean) = T.task { extraSources: Seq[os.Path] =>
    T.log.errorStream.println("Creating Antora playbook ...")
    // dependency to sources
    source()
    val docSite = T.dest
    val playbook = docSite / "antora-playbook.yml"
    val siteDir = docSite / "site"
    os.write(
      target = playbook,
      data = githubPagesPlaybookText(authorMode)().apply(extraSources),
      createFolders = true
    )
    T.log.errorStream.println("Running Antora ...")
    runAntora(
      npmDir = npmBase(),
      workDir = docSite,
      args = Seq(
        playbook.last,
        "--to-dir",
        siteDir.toString(),
        "--attribute",
        "page-pagination"
      ) ++
        Option.when(!authorMode)("--fetch").toSeq
    )
    os.write(siteDir / ".nojekyll", "")

    // sanitize devAntora source URLs
    T.log.errorStream.println("Sanitizing links ...")
    sanitizeDevUrls(siteDir, devAntoraSources().path, baseDir / "docs", baseDir)

    // only copy the "api" sub-dir; api docs contains a top-level index.html with we don't want
    val unidocSrc = if (authorMode) site.unidocLocal().path else site.unidocSite().path
    T.log.errorStream.println(s"Copying API docs from ${unidocSrc} ...")
    os.copy(unidocSrc, siteDir / "api" / "latest", createFolders = true)

    PathRef(siteDir)
  }

  def sanitizeDevUrls(
      dir: os.Path,
      sourceDir: os.Path,
      newSourceDir: os.Path,
      baseDir: os.Path
  ): Unit = {

    val pathToRemove = sourceDir.relativeTo(baseDir).toString()
    val replacePath = newSourceDir.relativeTo(baseDir).toString()
    //      println(s"Cleaning relative path '${pathToRemove}' ...")
    import org.htmlcleaner._
    val cleaner = new HtmlCleaner()
    var changed = false
    os.walk(dir).foreach { file =>
      if (os.isFile(file) && file.ext == "html") {
        val node: TagNode = cleaner.clean(file.toIO)
        node.traverse { (parentNode: TagNode, htmlNode: HtmlNode) =>
          htmlNode match {
            case tag: TagNode if tag.getName() == "a" =>
              Option(tag.getAttributeByName("href")).foreach { href =>
                val newHref = href.replace(pathToRemove, replacePath)
                if (href != newHref) {
                  tag.removeAttribute("href")
                  tag.addAttribute("href", newHref)
                  changed = true
                  println(s"Replaced: '${href}' --> '${newHref}'")
                }
              }
              true
            case _ => true
          }
        }
        if (changed) {
          println(s"Writing '${file}' ...")
          val newHtml = new SimpleHtmlSerializer(cleaner.getProperties()).getAsString(node)
          os.write.over(file, newHtml)
        }
      }
    }
  }
}

/**
 * Build and install Mill locally.
 *
 * @param binFile The location where the Mill binary should be installed
 * @param ivyRepo The local Ivy repository where Mill modules should be published to
 */
def installLocal(binFile: String = DefaultLocalMillReleasePath, ivyRepo: String = null) =
  T.command {
    PathRef(installLocalTask(T.task(binFile), ivyRepo)())
  }

def installLocalCache() = T.command {
  val path = installLocalTask(
    T.task((os.home / ".cache" / "mill" / "download" / millVersion()).toString())
  )()
  T.log.outputStream.println(path.toString())
  PathRef(path)
}

def installLocalTask(binFile: Task[String], ivyRepo: String = null): Task[os.Path] = T.task {
  val millBin = dist.assembly()
  val targetFile = os.Path(binFile(), T.workspace)
  if (os.exists(targetFile))
    T.log.info(s"Overwriting existing local Mill binary at ${targetFile}")
  os.copy.over(millBin.path, targetFile, createFolders = true)
  T.log.info(s"Published ${dist.allPublishModules.size} modules and installed ${targetFile}")
  targetFile
}

def millBootstrap = T.sources(T.workspace / "mill")

def bootstrapLauncher = T {
  val outputPath = T.dest / "mill"
  val millBootstrapGrepPrefix = "(\n *DEFAULT_MILL_VERSION=)"
  val millDownloadUrlPrefix = "(\n *MILL_DOWNLOAD_URL=)"

  os.write(
    outputPath,
    os.read(millBootstrap().head.path)
      .replaceAll(
        millBootstrapGrepPrefix + "[^\\n]+",
        "$1" + millVersion()
      )
  )
  os.perms.set(outputPath, "rwxrwxrwx")
  PathRef(outputPath)
}

def exampleZips: T[Seq[PathRef]] = T {
  for {
    exampleMod <- example.exampleModules
    examplePath = exampleMod.millSourcePath
  } yield {
    val example = examplePath.subRelativeTo(T.workspace)
    val exampleStr = millVersion() + "-" + example.segments.mkString("-")
    os.copy(examplePath, T.dest / exampleStr, createFolders = true)
    os.write(T.dest / exampleStr / ".mill-version", millLastTag())
    os.copy(bootstrapLauncher().path, T.dest / exampleStr / "mill")
    val zip = T.dest / s"$exampleStr.zip"
    os.proc("zip", "-r", zip, exampleStr).call(cwd = T.dest)
    PathRef(zip)
  }
}

def uploadToGithub(authKey: String) = T.command {
  val vcsState = VcsVersion.vcsState()
  val label = vcsState.format()
  if (label != millVersion()) sys.error("Modified mill version detected, aborting upload")
  val releaseTag = vcsState.lastTag.getOrElse(sys.error(
    "Incomplete git history. No tag found.\nIf on CI, make sure your git checkout job includes enough history."
  ))

  if (releaseTag == label) {
    // TODO: check if the tag already exists (e.g. because we created it manually) and do not fail
    scalaj.http.Http(
      s"https://api.github.com/repos/${Settings.githubOrg}/${Settings.githubRepo}/releases"
    )
      .postData(
        ujson.write(
          ujson.Obj(
            "tag_name" -> releaseTag,
            "name" -> releaseTag
          )
        )
      )
      .header("Authorization", "token " + authKey)
      .asString
  }

  val examples = exampleZips().map(z => (z.path, z.path.last))

  val zips = examples ++ Seq(
    (dist.assembly().path, label + "-assembly"),
    (bootstrapLauncher().path, label)
  )

  for ((zip, name) <- zips) {
    upload.apply(
      zip,
      releaseTag,
      name,
      authKey,
      Settings.githubOrg,
      Settings.githubRepo
    )
  }
}

private def resolveTasks[T](taskNames: String*): Seq[NamedTask[T]] = {
  mill.resolve.Resolve.Tasks.resolve(
    build,
    taskNames,
    SelectMode.Separated
  ).map(x => x.asInstanceOf[Seq[mill.define.NamedTask[T]]]).getOrElse(???)
}

def validate(): Command[Unit] = {
  val tasks = resolveTasks("__.compile", "__.minaReportBinaryIssues")
  val sources = resolveTasks("__.sources")

  T.command {
    T.sequence(tasks)()
    mill.scalalib.scalafmt.ScalafmtModule.checkFormatAll(Tasks(sources))()
    docs.localPages()
    ()
  }
}

val dummyDeps: Seq[Dep] = Seq(
  Deps.DocDeps.millScip,
  Deps.semanticDbJava,
  Deps.semanticDBscala,
  Deps.TestDeps.scalaTest,
  Deps.TestDeps.zioTest,
  Deps.acyclic,
  Deps.scalacScoverage2Plugin,
  ivy"com.lihaoyi:::ammonite:${Deps.ammoniteVersion}"
) ++ Deps.transitiveDeps ++ Deps.RuntimeDeps.all

implicit object DepSegment extends Cross.ToSegments[Dep]({ dep =>
      val depString = formatDep(dep)
      List(depString)
    })

/**
 * Dummy module(s) to let Dependency/showUpdates or Scala-Steward find
 * and bump dependency versions we use at runtime
 */
object dummy extends Cross[DependencyFetchDummy](dummyDeps)
trait DependencyFetchDummy extends ScalaModule with Cross.Module[Dep] {
  def scalaVersion = Deps.scalaVersion
  def compileIvyDeps = Agg(crossValue)
}
