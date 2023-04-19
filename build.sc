// plugins and dependencies
import $file.ci.shared
import $file.ci.upload
import $ivy.`org.scalaj::scalaj-http:2.4.2`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version_mill0.10:0.3.0`
import $ivy.`com.github.lolgab::mill-mima_mill0.10:0.0.13`
import $ivy.`net.sourceforge.htmlcleaner:htmlcleaner:2.25`

// imports
import com.github.lolgab.mill.mima
import com.github.lolgab.mill.mima.{
  CheckDirection,
  DirectMissingMethodProblem,
  IncompatibleMethTypeProblem,
  IncompatibleSignatureProblem,
  ProblemFilter,
  ReversedMissingMethodProblem
}
import coursier.maven.MavenRepository
import de.tobiasroeser.mill.vcs.version.VcsVersion
import mill._
import mill.define.{Command, Source, Sources, Target, Task}
import mill.eval.Evaluator
import mill.main.MainModule
import mill.scalalib._
import mill.scalalib.publish._
import mill.modules.Jvm
import mill.define.SelectMode

import scala.util.control.NonFatal
import mill.T
import mill.define.{Discover, ExternalModule, Input, Module, Task}
import mill.api.{Logger, Result}
import os.{CommandResult, SubprocessException}

object Settings {
  val pomOrg = "com.lihaoyi"
  val githubOrg = "com-lihaoyi"
  val githubRepo = "mill"
  val projectUrl = s"https://github.com/${githubOrg}/${githubRepo}"
  val changelogUrl = s"${projectUrl}#changelog"
  val docUrl = "https://com-lihaoyi.github.io/mill"
  // the exact branches containing a doc root
  val docBranches = Seq()
  // the exact tags containing a doc root
  val docTags: Seq[String] = Seq(
    "0.9.6",
    "0.9.7",
    "0.9.8",
    "0.9.9",
    "0.9.10",
    "0.9.11",
    "0.9.12",
    "0.10.0",
    "0.10.1",
    "0.10.2",
    "0.10.3",
    "0.10.4",
    "0.10.5",
    "0.10.6",
    "0.10.7",
    "0.10.8",
    "0.10.9",
    "0.10.10",
    "0.10.11",
    "0.10.12",
    "0.11.0-M1",
    "0.11.0-M2",
    "0.11.0-M3",
    "0.11.0-M4",
    "0.11.0-M5",
    "0.11.0-M6",
    "0.11.0-M7"
  )
  val mimaBaseVersions: Seq[String] = Seq("0.11.0-M7")
}

object Deps {

  // The Scala version to use
  val scalaVersion = "2.13.10"
  // Scoverage 1.x will not get releases for newer Scala versions
  val scalaVersionForScoverageWorker1 = "2.13.8"
  // The Scala 2.12.x version to use for some workers
  val workerScalaVersion212 = "2.12.17"

  val testScala213Version = "2.13.8"
  val testScala212Version = "2.12.6"
  val testScala211Version = "2.11.12"
  val testScala210Version = "2.10.6"
  val testScala30Version = "3.0.2"
  val testScala31Version = "3.1.3"
  val testScala32Version = "3.2.0"

  object Scalajs_1 {
    val scalaJsVersion = "1.13.0"
    val scalajsEnvJsdomNodejs = ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0"
    val scalajsEnvExoegoJsdomNodejs = ivy"net.exoego::scalajs-env-jsdom-nodejs:2.1.0"
    val scalajsEnvNodejs = ivy"org.scala-js::scalajs-env-nodejs:1.4.0"
    val scalajsEnvPhantomjs = ivy"org.scala-js::scalajs-env-phantomjs:1.0.0"
    val scalajsEnvSelenium = ivy"org.scala-js::scalajs-env-selenium:1.1.1"
    val scalajsSbtTestAdapter = ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJsVersion}"
    val scalajsLinker = ivy"org.scala-js::scalajs-linker:${scalaJsVersion}"
  }

  object Scalanative_0_4 {
    val scalanativeVersion = "0.4.12"
    val scalanativeTools = ivy"org.scala-native::tools:${scalanativeVersion}"
    val scalanativeUtil = ivy"org.scala-native::util:${scalanativeVersion}"
    val scalanativeNir = ivy"org.scala-native::nir:${scalanativeVersion}"
    val scalanativeTestRunner = ivy"org.scala-native::test-runner:${scalanativeVersion}"
  }

  trait Play {
    def playVersion: String
    def playBinVersion: String = playVersion.split("[.]").take(2).mkString(".")
    def routesCompiler = ivy"com.typesafe.play::routes-compiler::$playVersion"
    def scalaVersion: String = Deps.scalaVersion
  }
  object Play_2_6 extends Play {
    val playVersion = "2.6.25"
    override def scalaVersion: String = Deps.workerScalaVersion212
  }
  object Play_2_7 extends Play {
    val playVersion = "2.7.9"
  }
  object Play_2_8 extends Play {
    val playVersion = "2.8.19"
  }
  val play = Seq(Play_2_8, Play_2_7, Play_2_6).map(p => (p.playBinVersion, p)).toMap

  val acyclic = ivy"com.lihaoyi:::acyclic:0.3.6"
  val ammoniteVersion = "3.0.0-M0-6-34034262"
  val scalaparse = ivy"com.lihaoyi::scalaparse:3.0.1"
  val bloopConfig = ivy"ch.epfl.scala::bloop-config:1.5.5"
  val coursier = ivy"io.get-coursier::coursier:2.1.2"
  val coursierInterface = ivy"io.get-coursier:interface:1.0.15"

  val flywayCore = ivy"org.flywaydb:flyway-core:8.5.13"
  val graphvizJava = ivy"guru.nidi:graphviz-java-all-j2v8:0.18.1"
  val junixsocket = ivy"com.kohlschutter.junixsocket:junixsocket-core:2.6.2"

  val jgraphtCore = ivy"org.jgrapht:jgrapht-core:1.4.0" // 1.5.0+ dont support JDK8

  val jna = ivy"net.java.dev.jna:jna:5.13.0"
  val jnaPlatform = ivy"net.java.dev.jna:jna-platform:5.13.0"

  val junitInterface = ivy"com.github.sbt:junit-interface:0.13.3"
  val lambdaTest = ivy"de.tototec:de.tobiasroeser.lambdatest:0.8.0"
  val log4j2Core = ivy"org.apache.logging.log4j:log4j-core:2.20.0"
  val osLib = ivy"com.lihaoyi::os-lib:0.9.1"
  val mainargs = ivy"com.lihaoyi::mainargs:0.4.0"
  val millModuledefsVersion = "0.10.9"
  val millModuledefsString = s"com.lihaoyi::mill-moduledefs:${millModuledefsVersion}"
  val millModuledefs = ivy"${millModuledefsString}"
  val millModuledefsPlugin =
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${millModuledefsVersion}"
  val testng = ivy"org.testng:testng:7.5"
  val sbtTestInterface = ivy"org.scala-sbt:test-interface:1.0"
  val scalaCheck = ivy"org.scalacheck::scalacheck:1.17.0"
  def scalaCompiler(scalaVersion: String) = ivy"org.scala-lang:scala-compiler:${scalaVersion}"
  val scalafmtDynamic = ivy"org.scalameta::scalafmt-dynamic:3.7.3"
  val scalametaTrees = ivy"org.scalameta::trees:4.7.6"
  def scalaReflect(scalaVersion: String) = ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  val scalacScoveragePlugin = ivy"org.scoverage:::scalac-scoverage-plugin:1.4.11"
  val scoverage2Version = "2.0.8"
  val scalacScoverage2Plugin = ivy"org.scoverage:::scalac-scoverage-plugin:${scoverage2Version}"
  val scalacScoverage2Reporter = ivy"org.scoverage::scalac-scoverage-reporter:${scoverage2Version}"
  val scalacScoverage2Domain = ivy"org.scoverage::scalac-scoverage-domain:${scoverage2Version}"
  val scalacScoverage2Serializer =
    ivy"org.scoverage::scalac-scoverage-serializer:${scoverage2Version}"
  // keep in sync with doc/antora/antory.yml
  val semanticDB = ivy"org.scalameta:::semanticdb-scalac:4.7.6"
  val semanticDbJava = ivy"com.sourcegraph:semanticdb-java:0.8.13"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.3.0"
  val upickle = ivy"com.lihaoyi::upickle:3.1.0"
  val utest = ivy"com.lihaoyi::utest:0.8.1"
  val windowsAnsi = ivy"io.github.alexarchambault.windows-ansi:windows-ansi:0.0.5"
  val zinc = ivy"org.scala-sbt::zinc:1.8.0"
  // keep in sync with doc/antora/antory.yml
  val bsp4j = ivy"ch.epfl.scala:bsp4j:2.1.0-M4"
  val fansi = ivy"com.lihaoyi::fansi:0.4.0"
  val jarjarabrams = ivy"com.eed3si9n.jarjarabrams::jarjar-abrams-core:1.8.2"
  val requests = ivy"com.lihaoyi::requests:0.8.0"
}

def millVersion: T[String] = T { VcsVersion.vcsState().format() }
def millLastTag: T[String] = T {
  VcsVersion.vcsState().lastTag.getOrElse(
    sys.error("No (last) git tag found. Your git history seems incomplete!")
  )
}
def millBinPlatform: T[String] = T {
  val tag = millLastTag()
  if (tag.contains("-M")) tag
  else {
    val pos = if (tag.startsWith("0.")) 2 else 1
    tag.split("[.]", pos + 1).take(pos).mkString(".")
  }
}
def baseDir = build.millSourcePath

// We limit the number of compiler bridges to compile and publish for local
// development and testing, because otherwise it takes forever to compile all
// of them. Compiler bridges not in this set will get downloaded and compiled
// on the fly anyway. For publishing, we publish everything.
val buildAllCompilerBridges = interp.watchValue(sys.env.contains("MILL_BUILD_COMPILER_BRIDGES"))
val bridgeVersion = "0.0.1"
val bridgeScalaVersions = Seq(
  // Our version of Zinc doesn't work with Scala 2.12.0 and 2.12.4 compiler
  // bridges. We skip 2.12.1 because it's so old not to matter, and we need a
  // non-supported scala versionm for testing purposes. We skip 2.13.0-2 because
  // scaladoc fails on windows
  /*"2.12.0",*/ /*2.12.1",*/ "2.12.2", "2.12.3", /*"2.12.4",*/ "2.12.5", "2.12.6", "2.12.7", "2.12.8",
  "2.12.9", "2.12.10", "2.12.11", "2.12.12", "2.12.13", "2.12.14", "2.12.15", "2.12.16", "2.12.17",
  /*"2.13.0", "2.13.1", "2.13.2",*/ "2.13.3", "2.13.4", "2.13.5", "2.13.6", "2.13.7", "2.13.8", "2.13.9", "2.13.10"
)
val buildBridgeScalaVersions =
  if (!buildAllCompilerBridges) Seq()
  else bridgeScalaVersions

object bridge extends Cross[BridgeModule](buildBridgeScalaVersions: _*)
class BridgeModule(val crossScalaVersion: String) extends PublishModule with CrossScalaModule {
  def scalaVersion = crossScalaVersion
  def publishVersion = bridgeVersion
  def artifactName = T{ "mill-scala-compiler-bridge" }
  def pomSettings = commonPomSettings(artifactName())
  def crossFullScalaVersion = true
  def ivyDeps = Agg(
    ivy"org.scala-sbt:compiler-interface:${Versions.zinc}",
    ivy"org.scala-lang:scala-compiler:${crossScalaVersion}",
  )

  def resources = T.sources {
    os.copy(generatedSources().head.path / "META-INF", T.dest / "META-INF")
    Seq(PathRef(T.dest))
  }

  def generatedSources = T {
    import mill.scalalib.api.ZincWorkerUtil.{grepJar, scalaBinaryVersion}
    val resolvedJars = resolveDeps(
      T.task { Agg(ivy"org.scala-sbt::compiler-bridge:${Deps.zinc.dep.version}") },
      sources = true
    )()

    val bridgeJar = grepJar(
      resolvedJars.map(_.path),
      s"compiler-bridge_${scalaBinaryVersion(scalaVersion())}",
      Deps.zinc.dep.version,
      true
    )

    mill.api.IO.unpackZip(bridgeJar, os.rel)

    Seq(PathRef(T.dest))
  }
}


trait BuildInfo extends JavaModule {
  /**
   * The package name under which the BuildInfo data object will be stored.
   */
  def buildInfoPackageName: String

  /**
   * The name of the BuildInfo data object, defaults to "BuildInfo"
   */
  def buildInfoObjectName: String = "BuildInfo"

  /**
   * Enable to compile the BuildInfo values directly into the classfiles,
   * rather than the default behavior of storing them as a JVM resource. Needed
   * to use BuildInfo on Scala.js which does not support JVM resources
   */
  def buildInfoStaticCompiled: Boolean = false

  /**
   * A mapping of key-value pairs to pass from the Build script to the
   * application code at runtime.
   */
  def buildInfoMembers: T[Seq[BuildInfo.Value]] = Seq.empty[BuildInfo.Value]

  def resources =
    if (buildInfoStaticCompiled) super.resources
    else T.sources{ super.resources() ++ Seq(buildInfoResources()) }

  def buildInfoResources = T{
    val p = new java.util.Properties
    for (v <- buildInfoMembers()) p.setProperty(v.key, v.value)

    val stream = os.write.outputStream(
      T.dest / os.SubPath(buildInfoPackageName.replace('.', '/')) / s"$buildInfoObjectName.buildinfo.properties",
      createFolders = true
    )

    p.store(stream, s"mill.contrib.buildinfo.BuildInfo for ${buildInfoPackageName}.${buildInfoObjectName}")
    stream.close()
    PathRef(T.dest)
  }

  private def isScala = this.isInstanceOf[ScalaModule]

  override def generatedSources = T {
    super.generatedSources() ++ buildInfoSources()
  }

  def buildInfoSources = T{
    if (buildInfoMembers().isEmpty) Nil
    else {
      val code = if (buildInfoStaticCompiled) BuildInfo.staticCompiledCodegen(
        buildInfoMembers(), isScala, buildInfoPackageName, buildInfoObjectName
      ) else BuildInfo.codegen(
        buildInfoMembers(), isScala, buildInfoPackageName, buildInfoObjectName
      )

      val ext = if (isScala) "scala" else "java"

      os.write(
        T.dest / buildInfoPackageName.split('.') / s"${buildInfoObjectName}.$ext",
        code,
        createFolders = true
      )
      Seq(PathRef(T.dest))
    }
  }
}

object BuildInfo{
  case class Value(key: String, value: String, comment: String = "")
  object Value{
    implicit val rw: upickle.default.ReadWriter[Value] = upickle.default.macroRW
  }
  def staticCompiledCodegen(buildInfoMembers: Seq[Value],
                            isScala: Boolean,
                            buildInfoPackageName: String,
                            buildInfoObjectName: String): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          if (isScala) s"""${commentStr(v)}val ${v.key} = ${pprint.Util.literalize(v.value)}"""
          else s"""${commentStr(v)}public static java.lang.String ${v.key} = ${pprint.Util.literalize(v.value)};"""
      }
      .mkString("\n\n  ")


    if (isScala) {
      val mapEntries = buildInfoMembers
        .map { case v => s""""${v.key}" -> ${v.key}"""}
        .mkString(",\n")

      s"""
         |package $buildInfoPackageName
         |
         |object $buildInfoObjectName {
         |  $bindingsCode
         |  val toMap = Map[String, String](
         |    $mapEntries
         |  )
         |}
      """.stripMargin.trim
    } else {
      val mapEntries = buildInfoMembers
        .map { case v => s"""map.put("${v.key}", ${v.key});""" }
        .mkString(",\n")

      s"""
         |package $buildInfoPackageName;
         |
         |public class $buildInfoObjectName {
         |  $bindingsCode
         |
         |  public static java.util.Map<String, String> toMap(){
         |    Map<String, String> map = new HashMap<String, String>();
         |    $mapEntries
         |    return map;
         |  }
         |}
      """.stripMargin.trim
    }
  }

  def codegen(buildInfoMembers: Seq[Value],
              isScala: Boolean,
              buildInfoPackageName: String,
              buildInfoObjectName: String): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          if (isScala) s"""${commentStr(v)}val ${v.key} = buildInfoProperties.getProperty("${v.key}")"""
          else s"""${commentStr(v)}public static final java.lang.String ${v.key} = buildInfoProperties.getProperty("${v.key}");"""
      }
      .mkString("\n\n  ")

    if (isScala)
      s"""
         |package ${buildInfoPackageName}
         |
         |object $buildInfoObjectName {
         |  private val buildInfoProperties = new java.util.Properties
         |
         |  private val buildInfoInputStream = getClass
         |    .getResourceAsStream("$buildInfoObjectName.buildinfo.properties")
         |
         |  buildInfoProperties.load(buildInfoInputStream)
         |
         |  $bindingsCode
         |}
      """.stripMargin.trim
    else
      s"""
         |package ${buildInfoPackageName};
         |
         |public class $buildInfoObjectName {
         |  private static java.util.Properties buildInfoProperties = new java.util.Properties();
         |
         |  static {
         |    java.io.InputStream buildInfoInputStream = $buildInfoObjectName
         |      .class
         |      .getResourceAsStream("$buildInfoObjectName.buildinfo.properties");
         |
         |    try{
         |      buildInfoProperties.load(buildInfoInputStream);
         |    }catch(java.io.IOException e){
         |      throw new RuntimeException(e);
         |    }finally{
         |      try{
         |        buildInfoInputStream.close();
         |      }catch(java.io.IOException e){
         |        throw new RuntimeException(e);
         |      }
         |    }
         |  }
         |
         |  $bindingsCode
         |}
      """.stripMargin.trim
  }

  def commentStr(v: Value) = {
    if (v.comment.isEmpty) ""
    else {
      val lines = v.comment.linesIterator.toVector
      lines.length match{
        case 1 => s"""/** ${v.comment} */\n  """
        case _ => s"""/**\n    ${lines.map("* " + _).mkString("\n    ")}\n    */\n  """
      }

    }
  }
}

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

trait MillPublishModule extends PublishModule {
  override def artifactName = "mill-" + super.artifactName()
  def publishVersion = millVersion()
  override def publishProperties: Target[Map[String, String]] = super.publishProperties() ++ Map(
    "info.releaseNotesURL" -> Settings.changelogUrl
  )
  def pomSettings = commonPomSettings(artifactName())
  override def javacOptions = Seq("-source", "1.8", "-target", "1.8", "-encoding", "UTF-8")
}

trait MillCoursierModule extends CoursierModule {
  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository(
        "https://oss.sonatype.org/content/repositories/releases"
      )
    )
  }
  override def mapDependencies: Task[coursier.Dependency => coursier.Dependency] = T.task {
    super.mapDependencies().andThen { dep =>
      forcedVersions.find(t =>
        t._1 == dep.module.organization.value && t._2 == dep.module.name.value
      ).map { forced =>
        val newDep = dep.withVersion(forced._3)
        T.log.debug(s"Forcing version of ${dep.module} from ${dep.version} to ${newDep.version}")
        newDep
      }.getOrElse(dep)
    }
  }
  val forcedVersions: Seq[(String, String, String)] = Seq(
    ("org.apache.ant", "ant", "1.10.12"),
    ("commons-io", "commons-io", "2.11.0"),
    ("com.google.code.gson", "gson", "2.10.1"),
    ("com.google.protobuf", "protobuf-java", "3.21.8"),
    ("com.google.guava", "guava", "31.1-jre"),
    ("org.yaml", "snakeyaml", "1.33")
  )
}

trait MillMimaConfig extends mima.Mima {
  def skipPreviousVersions: T[Seq[String]] = T(Seq.empty[String])
  override def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions
  override def mimaPreviousArtifacts: T[Agg[Dep]] = T {
    Agg.from(
      Settings.mimaBaseVersions
        .filter(v => !skipPreviousVersions().contains(v))
        .map(version =>
          ivy"${pomSettings().organization}:${artifactId()}:${version}"
        )
    )
  }
  override def mimaExcludeAnnotations: T[Seq[String]] = Seq(
    "mill.api.internal",
    "mill.api.experimental"
  )
  override def mimaCheckDirection: Target[CheckDirection] = T { CheckDirection.Backward }
  override def mimaBinaryIssueFilters: Target[Seq[ProblemFilter]] = T {
    issueFilterByModule.getOrElse(this, Seq())
  }
  lazy val issueFilterByModule: Map[MillMimaConfig, Seq[ProblemFilter]] = Map()
}

/** A Module compiled with applied Mill-specific compiler plugins: mill-moduledefs. */
trait WithMillCompiler extends ScalaModule {
  override def ivyDeps: T[Agg[Dep]] = super.ivyDeps() ++ Agg(Deps.millModuledefs)
  override def scalacPluginIvyDeps: Target[Agg[Dep]] =
    super.scalacPluginIvyDeps() ++ Agg(Deps.millModuledefsPlugin)
}

trait AcyclicConfig extends ScalaModule {
  override def scalacPluginIvyDeps: Target[Agg[Dep]] = {
    super.scalacPluginIvyDeps() ++ Agg(Deps.acyclic)
  }
  override def scalacOptions: Target[Seq[String]] =
    super.scalacOptions() ++ Seq("-P:acyclic:force", "-P:acyclic:warn")
}

/**
 * Some custom scala settings and test convenience
 */
trait MillScalaModule extends ScalaModule with MillCoursierModule { outer =>
  def scalaVersion = Deps.scalaVersion
  override def scalacOptions = T {
    super.scalacOptions() ++ Seq("-deprecation")
  }

  // Test setup

  def testArgs = T { Seq.empty[String] }
  def testIvyDeps: T[Agg[Dep]] = Agg(Deps.utest)
  def testModuleDeps: Seq[JavaModule] =
    if (this == main) Seq(main)
    else Seq(this, main.test)

  trait MillScalaModuleTests extends ScalaModuleTests with MillCoursierModule
      with WithMillCompiler with BaseMillTestsModule {

    override def forkArgs = super.forkArgs() ++ outer.testArgs()
    override def moduleDeps = outer.testModuleDeps
    override def ivyDeps: T[Agg[Dep]] = T { super.ivyDeps() ++ outer.testIvyDeps() }

  }
  trait Tests extends MillScalaModuleTests
}

trait BaseMillTestsModule extends TestModule {
  override def forkArgs = T {
    Seq(
      s"-DMILL_SCALA_2_13_VERSION=${Deps.scalaVersion}",
      s"-DMILL_SCALA_2_12_VERSION=${Deps.workerScalaVersion212}",
      s"-DTEST_SCALA_2_13_VERSION=${Deps.testScala213Version}",
      s"-DTEST_SCALA_2_12_VERSION=${Deps.testScala212Version}",
      s"-DTEST_SCALA_2_11_VERSION=${Deps.testScala211Version}",
      s"-DTEST_SCALA_2_10_VERSION=${Deps.testScala210Version}",
      s"-DTEST_SCALA_3_0_VERSION=${Deps.testScala30Version}",
      s"-DTEST_SCALA_3_1_VERSION=${Deps.testScala31Version}",
      s"-DTEST_SCALA_3_2_VERSION=${Deps.testScala32Version}",
      s"-DTEST_SCALAJS_VERSION=${Deps.Scalajs_1.scalaJsVersion}",
      s"-DTEST_SCALANATIVE_VERSION=${Deps.Scalanative_0_4.scalanativeVersion}",
      s"-DTEST_UTEST_VERSION=${Deps.utest.dep.version}"
    )
  }
  override def testFramework = "mill.UTestFramework"
}

/** A MillScalaModule with default set up test module. */
trait MillAutoTestSetup extends MillScalaModule {
  // instead of `object test` which can't be overridden, we hand-made a val+class singleton
  /** Default tests module. */
  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends mill.Module()(ctx0) with super.MillScalaModuleTests
}

/** Published module which does not contain strictly handled API. */
trait MillInternalModule extends MillScalaModule with MillPublishModule

/** Publishable module which contains strictly handled API. */
trait MillApiModule extends MillScalaModule with MillPublishModule with MillMimaConfig

/** Publishable module with tests. */
trait MillModule extends MillApiModule with MillAutoTestSetup with WithMillCompiler
    with AcyclicConfig

object main extends MillModule {

  override def moduleDeps = Seq(core, client)
  override def ivyDeps = Agg(
    Deps.windowsAnsi,
    Deps.mainargs,
    Deps.coursierInterface,
    Deps.requests
  )
  override def compileIvyDeps = Agg(
    Deps.scalaReflect(scalaVersion())
  )
  override def testArgs = Seq(
    "-DMILL_VERSION=" + publishVersion()
  )

  object api extends MillApiModule with BuildInfo{
    def buildInfoPackageName = "mill.api"
    def buildInfoMembers = Seq(BuildInfo.Value("millVersion", millVersion(), "Mill version."))
    override def ivyDeps = Agg(
      Deps.osLib,
      Deps.upickle,
      Deps.fansi,
      Deps.sbtTestInterface
    )
  }
  object util extends MillApiModule with MillAutoTestSetup {
    override def moduleDeps = Seq(api)
    override def ivyDeps = Agg(
      Deps.fansi
    )
  }
  object core extends MillModule with BuildInfo{
    
    override def moduleDeps = Seq(api, util)
    override def compileIvyDeps = Agg(
      Deps.scalaReflect(scalaVersion())
    )
    override def ivyDeps = Agg(
      Deps.millModuledefs,
      Deps.millModuledefsPlugin,
      Deps.scalametaTrees,
      Deps.coursier,
      // Necessary so we can share the JNA classes throughout the build process
      Deps.jna,
      Deps.jnaPlatform,
      Deps.jarjarabrams,
      Deps.mainargs,
      Deps.scalaparse
    )

    def buildInfoPackageName = "mill"
    
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion(), "Scala version used to compile mill core."),
      BuildInfo.Value("workerScalaVersion212", Deps.workerScalaVersion212, "Scala 2.12 version used by some workers."),
      BuildInfo.Value("millVersion", millVersion(), "Mill version."),
      BuildInfo.Value("millBinPlatform", millBinPlatform(), "Mill binary platform version."),
      BuildInfo.Value("millEmbeddedDeps",
        T.traverse(dev.moduleDeps)(_.publishSelfDependency)()
          .map(artifact => s"${artifact.group}:${artifact.id}:${artifact.version}")
          .mkString(","),
        "Dependency artifacts embedded in mill assembly by default."
      ),
      BuildInfo.Value("millScalacPluginDeps", Deps.millModuledefsString, "Scalac compiler plugin dependencies to compile the build script."),
      BuildInfo.Value("millDocUrl", Settings.docUrl, "Mill documentation url.")
    )
  }

  object client extends MillPublishModule with BuildInfo{
    def buildInfoPackageName = "mill.main.client"
    def buildInfoMembers = Seq(BuildInfo.Value("millVersion", millVersion(), "Mill version."))
    override def ivyDeps = Agg(Deps.junixsocket)

    object test extends Tests with TestModule.Junit4 {
      override def ivyDeps = Agg(Deps.junitInterface, Deps.lambdaTest)
    }
  }

  object graphviz extends MillModule {
    override def moduleDeps = Seq(main, scalalib)

    override def ivyDeps = Agg(
      Deps.graphvizJava,
      Deps.jgraphtCore
    )
    override def testArgs = Seq(
      "-DMILL_GRAPHVIZ=" + runClasspath().map(_.path).mkString(",")
    )
  }

  object testkit extends MillInternalModule with MillAutoTestSetup {
    def moduleDeps = Seq(core, util)
  }

  def testModuleDeps = super.testModuleDeps ++ Seq(testkit)
}

object testrunner extends MillModule {
  override def moduleDeps = Seq(scalalib.api, main.util)
}


object scalalib extends MillModule {
  override def moduleDeps = Seq(main, scalalib.api, testrunner)

  override def ivyDeps = Agg(
    Deps.scalafmtDynamic
  )

  override def testIvyDeps = super.testIvyDeps() ++ Agg(Deps.scalaCheck)

  def testArgs = T {

    val artifactsString =
      T.traverse(dev.moduleDeps)(_.publishSelfDependency)()
        .map(artifact => s"${artifact.group}:${artifact.id}:${artifact.version}")
        .mkString(",")

    worker.testArgs() ++
      main.graphviz.testArgs() ++
      Seq(
        "-Djna.nosys=true",
        "-DMILL_SCALA_LIB=" + runClasspath().map(_.path).mkString(","),
        s"-DTEST_SCALAFMT_VERSION=${Deps.scalafmtDynamic.dep.version}",
        s"-DMILL_EMBEDDED_DEPS=$artifactsString"
      )
  }
  object backgroundwrapper extends MillPublishModule {
    override def ivyDeps = Agg(
      Deps.sbtTestInterface
    )
    def testArgs = T {
      Seq(
        "-DMILL_BACKGROUNDWRAPPER=" + runClasspath().map(_.path).mkString(",")
      )
    }
  }
  object api extends MillApiModule with BuildInfo {
    override def moduleDeps = Seq(main.api)

    def buildInfoPackageName = "mill.scalalib.api"

    def buildInfoObjectName = "Versions"

    def buildInfoMembers = Seq(
      BuildInfo.Value("ammonite", Deps.ammoniteVersion, "Version of Ammonite."),
      BuildInfo.Value("zinc", Deps.zinc.dep.version, "Version of Zinc"),
      BuildInfo.Value("semanticDBVersion", Deps.semanticDB.dep.version, "SemanticDB version."),
      BuildInfo.Value("semanticDbJavaVersion", Deps.semanticDbJava.dep.version, "Java SemanticDB plugin version."),
      BuildInfo.Value("millModuledefsVersion", Deps.millModuledefsVersion, "Mill ModuleDefs plugins version."),
      BuildInfo.Value("millCompilerBridgeScalaVersions", bridgeScalaVersions.mkString(",")),
      BuildInfo.Value("millCompilerBridgeVersion", bridgeVersion),
      BuildInfo.Value("millVersion", millVersion(), "Mill version.")
    )
  }

  object worker extends MillInternalModule with BuildInfo{

    override def moduleDeps = Seq(scalalib.api)

    override def ivyDeps = Agg(
      Deps.zinc,
      Deps.log4j2Core
    )
    def testArgs = Seq("-DMILL_SCALA_WORKER=" + runClasspath().map(_.path).mkString(","))

    def buildInfoPackageName = "mill.scalalib.worker"
    def buildInfoObjectName = "Versions"
    def buildInfoMembers = Seq(BuildInfo.Value("zinc", Deps.zinc.dep.version, "Version of Zinc."))
  }
}

object scalajslib extends MillModule with BuildInfo{

  override def moduleDeps = Seq(scalalib, scalajslib.`worker-api`)

  override def testArgs = T {
    val mapping = Map(
      "MILL_SCALAJS_WORKER_1" -> worker("1").compile().classes.path
    )
    Seq("-Djna.nosys=true") ++
      scalalib.worker.testArgs() ++
      scalalib.backgroundwrapper.testArgs() ++
      (for ((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
  }

  def buildInfoPackageName = "mill.scalajslib"
  def buildInfoObjectName = "ScalaJSBuildInfo"

  def buildInfoMembers = T{
    val resolve = resolveCoursierDependency()

    def formatDep(dep: Dep) = {
      val d = resolve(dep)
      s"${d.module.organization.value}:${d.module.name.value}:${d.version}"
    }

    Seq(
      BuildInfo.Value("scalajsEnvNodejs", formatDep(Deps.Scalajs_1.scalajsEnvNodejs)),
      BuildInfo.Value("scalajsEnvJsdomNodejs", formatDep(Deps.Scalajs_1.scalajsEnvJsdomNodejs)),
      BuildInfo.Value("scalajsEnvExoegoJsdomNodejs", formatDep(Deps.Scalajs_1.scalajsEnvExoegoJsdomNodejs)),
      BuildInfo.Value("scalajsEnvPhantomJs", formatDep(Deps.Scalajs_1.scalajsEnvPhantomjs)),
      BuildInfo.Value("scalajsEnvSelenium", formatDep(Deps.Scalajs_1.scalajsEnvSelenium))
    )
  }

  object `worker-api` extends MillInternalModule {
    override def ivyDeps = Agg(Deps.sbtTestInterface)
  }
  object worker extends Cross[WorkerModule]("1")
  class WorkerModule(scalajsWorkerVersion: String) extends MillInternalModule {
    override def moduleDeps = Seq(scalajslib.`worker-api`, main.client, main.api)
    override def ivyDeps = Agg(
      Deps.Scalajs_1.scalajsLinker,
      Deps.Scalajs_1.scalajsSbtTestAdapter,
      Deps.Scalajs_1.scalajsEnvNodejs,
      Deps.Scalajs_1.scalajsEnvJsdomNodejs,
      Deps.Scalajs_1.scalajsEnvExoegoJsdomNodejs,
      Deps.Scalajs_1.scalajsEnvPhantomjs,
      Deps.Scalajs_1.scalajsEnvSelenium
    )
  }
}

object contrib extends MillModule {
  object testng extends JavaModule with MillModule {
    // pure Java implementation
    override def artifactSuffix: T[String] = ""
    override def scalaLibraryIvyDeps: Target[Agg[Dep]] = T { Agg.empty[Dep] }
    override def ivyDeps = Agg(Deps.sbtTestInterface)
    override def compileIvyDeps = Agg(Deps.testng)
    override def runIvyDeps = Agg(Deps.testng)
    override def testArgs = T {
      Seq(
        "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(","),
        "-DMILL_TESTNG_LIB=" + runClasspath().map(_.path).mkString(",")
      ) ++ scalalib.worker.testArgs()
    }
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(
      scalalib
    )
    override def docJar: T[PathRef] = super[JavaModule].docJar
  }

  object twirllib extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object playlib extends MillModule {
    override def moduleDeps = Seq(twirllib, playlib.api)
    override def compileModuleDeps = Seq(scalalib)

    override def testArgs = T {
      val mapping = Map(
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_6" -> worker("2.6").assembly().path,
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_7" -> worker("2.7").assembly().path,
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_8" -> worker("2.8").assembly().path,
        "TEST_PLAY_VERSION_2_6" -> Deps.Play_2_6.playVersion,
        "TEST_PLAY_VERSION_2_7" -> Deps.Play_2_7.playVersion,
        "TEST_PLAY_VERSION_2_8" -> Deps.Play_2_8.playVersion
      )

      scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        (for ((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
    }
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)

    object api extends MillPublishModule

    object worker extends Cross[WorkerModule](Deps.play.keys.toSeq: _*)
    class WorkerModule(playBinary: String) extends MillInternalModule {
      override def sources = T.sources {
        // We want to avoid duplicating code as long as the Play APIs allow.
        // But if newer Play versions introduce incompatibilities,
        // just remove the shared source dir for that worker and implement directly.
        Seq(PathRef(millSourcePath / os.up / "src-shared")) ++ super.sources()
      }
      override def scalaVersion = Deps.play(playBinary).scalaVersion
      override def moduleDeps = Seq(playlib.api)
      override def ivyDeps = Agg(
        Deps.osLib,
        Deps.play(playBinary).routesCompiler
      )
    }
  }

  object scalapblib extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object scoverage extends MillModule {
    object api extends MillApiModule {
      override def compileModuleDeps = Seq(main.api)
    }
    override def moduleDeps = Seq(scoverage.api)
    override def compileModuleDeps = Seq(scalalib)

    override def testArgs = T {
      val mapping = Map(
        "MILL_SCOVERAGE_REPORT_WORKER" -> worker.compile().classes.path,
        "MILL_SCOVERAGE2_REPORT_WORKER" -> worker2.compile().classes.path,
        "MILL_SCOVERAGE_VERSION" -> Deps.scalacScoveragePlugin.dep.version,
        "MILL_SCOVERAGE2_VERSION" -> Deps.scalacScoverage2Plugin.dep.version,
        "TEST_SCALA_2_12_VERSION" -> "2.12.15" // last supported 2.12 version for Scoverage 1.x
      )
      scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        (for ((k, v) <- mapping) yield s"-D$k=$v")
    }

    // So we can test with buildinfo in the classpath
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(
      scalalib,
      contrib.buildinfo
    )

    // Worker for Scoverage 1.x
    object worker extends MillInternalModule {
      override def compileModuleDeps = Seq(main.api)
      override def moduleDeps = Seq(scoverage.api)
      override def compileIvyDeps = T {
        Agg(
          // compile-time only, need to provide the correct scoverage version at runtime
          Deps.scalacScoveragePlugin,
          // provided by mill runtime
          Deps.osLib
        )
      }
      override def scalaVersion: Target[String] = Deps.scalaVersionForScoverageWorker1
    }

    // Worker for Scoverage 2.0
    object worker2 extends MillInternalModule {
      override def compileModuleDeps = Seq(main.api)
      override def moduleDeps = Seq(scoverage.api)
      override def compileIvyDeps = T {
        Agg(
          // compile-time only, need to provide the correct scoverage version at runtime
          Deps.scalacScoverage2Plugin,
          Deps.scalacScoverage2Reporter,
          Deps.scalacScoverage2Domain,
          Deps.scalacScoverage2Serializer,
          // provided by mill runtime
          Deps.osLib
        )
      }
    }
  }

  object buildinfo extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    // why do I need this?
    override def testArgs = T {
      Seq("-Djna.nosys=true") ++
        scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs()
    }
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object proguard extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def testArgs = T {
      Seq(
        "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(","),
        "-DMILL_PROGUARD_LIB=" + runClasspath().map(_.path).mkString(",")
      ) ++ scalalib.worker.testArgs()
    }
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object flyway extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def ivyDeps = Agg(Deps.flywayCore)
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object docker extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def testArgs = T {
      Seq("-Djna.nosys=true") ++
        scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs()
    }
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }

  object bloop extends MillModule with BuildInfo {
    override def compileModuleDeps = Seq(scalalib, scalajslib, scalanativelib)
    override def ivyDeps = Agg(
      Deps.bloopConfig.exclude("*" -> s"jsoniter-scala-core_2.13")
    )
    override def testArgs = T(scalanativelib.testArgs())
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(
      scalalib,
      scalajslib,
      scalanativelib
    )
    
    def buildInfoPackageName = "mill.contrib.bloop" 
    def buildInfoObjectName = "Versions"
    def buildInfoMembers = Seq(BuildInfo.Value("bloop", Deps.bloopConfig.dep.version))
  }

  object artifactory extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def ivyDeps = T { Agg(Deps.requests) }
  }

  object codeartifact extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def ivyDeps = T { Agg(Deps.requests) }
  }

  object versionfile extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object bintray extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def ivyDeps = T { Agg(Deps.requests) }
  }

  object gitlab extends MillInternalModule with MillAutoTestSetup {
    override def compileModuleDeps = Seq(scalalib)
    override def ivyDeps = T { Agg(Deps.requests, Deps.osLib) }

    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(
      scalalib
    )
  }

  object jmh extends MillInternalModule with MillAutoTestSetup with WithMillCompiler {
    override def compileModuleDeps = Seq(scalalib)
    override def testArgs = T {
      Seq(
        "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(",")
      ) ++ scalalib.worker.testArgs()
    }
    override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ Seq(scalalib)
  }
}

object scalanativelib extends MillModule {
  override def moduleDeps = Seq(scalalib, scalanativelib.`worker-api`)

  override def testArgs = T {
    val mapping = Map(
      "MILL_SCALANATIVE_WORKER_0_4" -> worker("0.4").compile().classes.path
    )
    scalalib.worker.testArgs() ++
      scalalib.backgroundwrapper.testArgs() ++
      (for ((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
  }

  object `worker-api` extends MillInternalModule {
    override def ivyDeps = Agg(Deps.sbtTestInterface)
  }
  object worker extends Cross[WorkerModule]("0.4")
  class WorkerModule(scalaNativeWorkerVersion: String)
      extends MillInternalModule {
    override def moduleDeps = Seq(scalanativelib.`worker-api`)
    override def ivyDeps = scalaNativeWorkerVersion match {
      case "0.4" =>
        Agg(
          Deps.osLib,
          Deps.Scalanative_0_4.scalanativeTools,
          Deps.Scalanative_0_4.scalanativeUtil,
          Deps.Scalanative_0_4.scalanativeNir,
          Deps.Scalanative_0_4.scalanativeTestRunner
        )
    }
  }
}

object bsp extends MillModule with BuildInfo{
  override def compileModuleDeps = Seq(scalalib)
  override def testModuleDeps: Seq[JavaModule] = super.testModuleDeps ++ compileModuleDeps

  def buildInfoPackageName = "mill.bsp"
  def buildInfoMembers = T{
    val workerDep = worker.publishSelfDependency()
    Seq(
      BuildInfo.Value("bsp4jVersion", Deps.bsp4j.dep.version, "BSP4j version (BSP Protocol version).")
    )
  }

  override val test = new Test(implicitly)
  class Test(ctx0: mill.define.Ctx) extends Tests(ctx0) {
    override def forkEnv: Target[Map[String, String]] = T {
      // We try to fetch this dependency with coursier in the tests
      bsp.worker.publishLocal()()
      super.forkEnv()
    }

    override def forkArgs: Target[Seq[String]] = super.forkArgs() ++ Seq(
      s"-DBSP4J_VERSION=${Deps.bsp4j.dep.version}"
    )
  }

  object worker extends MillInternalModule with BuildInfo{
    override def compileModuleDeps = Seq(bsp, scalalib, testrunner)
    override def ivyDeps = Agg(
      Deps.bsp4j,
      Deps.sbtTestInterface
    )

    def buildInfoPackageName = "mill.bsp.worker"
    def buildInfoMembers = T{
      val workerDep = worker.publishSelfDependency()
      Seq(
        BuildInfo.Value("bsp4jVersion", Deps.bsp4j.dep.version, "BSP4j version (BSP Protocol version)."),
        BuildInfo.Value("millBspWorkerVersion", workerDep.version, "BSP worker dependency.")
      )
    }
  }
}

val DefaultLocalMillReleasePath =
  s"target/mill-release${if (scala.util.Properties.isWin) ".bat" else ""}"

/**
 * Build and install Mill locally.
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

def installLocalTask(binFile: Task[String], ivyRepo: String = null): Task[os.Path] = {
  val modules = build.millInternal.modules.collect{
    case m: PublishModule => m
  }

  T.task {
    T.traverse(modules)(m => m.publishLocal(ivyRepo))()
    val millBin = assembly()
    val targetFile = os.Path(binFile(), T.workspace)
    if (os.exists(targetFile))
      T.log.info(s"Overwriting existing local Mill binary at ${targetFile}")
    os.copy.over(millBin.path, targetFile, createFolders = true)
    T.log.info(s"Published ${modules.size} modules and installed ${targetFile}")
    targetFile
  }
}

// We compile the test code once and then offer multiple modes to
// test it in the `test` CrossModule. We pass `test`'s sources to `lib` to
// and pass `lib`'s compile output back to `test`
trait IntegrationTestModule extends MillScalaModule {
  def repoSlug: String

  def scalaVersion = integration.scalaVersion()
  def moduleDeps = Seq(main.test, integration)
  def sources = T.sources(millSourcePath / "test" / "src")
  def testRepoRoot: T[PathRef] = T.source(millSourcePath / "repo")

  trait ModeModule extends MillScalaModule with BaseMillTestsModule {
    def mode: String = millModuleSegments.parts.last

    def scalaVersion = integration.scalaVersion()

    override def forkEnv =
      super.forkEnv() ++
      IntegrationTestModule.this.forkEnv() ++
      Map(
        "MILL_INTEGRATION_TEST_MODE" -> mode,
        "MILL_INTEGRATION_TEST_SLUG" -> repoSlug,
        "MILL_INTEGRATION_REPO_ROOT" -> testRepoRoot().path.toString
      ) ++
      testReleaseEnv()

    def workspaceDir = T.persistent {
      PathRef(T.dest)
    }

    def genTask(m: ScalaModule) = T.task {
      Seq(m.jar(), m.sourceJar()) ++ m.runClasspath()
    }

    override def forkArgs: Target[Seq[String]] = T {
      val genIdeaArgs =
        //      genTask(main.moduledefs)() ++
        genTask(main.core)() ++
          genTask(main)() ++
          genTask(scalalib)() ++
          genTask(scalajslib)() ++
          genTask(scalanativelib)()

      super.forkArgs() ++
        scalajslib.testArgs() ++
        scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        scalanativelib.testArgs() ++
        runner.linenumbers.testArgs() ++
        Seq(
          s"-DMILL_WORKSPACE_PATH=${workspaceDir().path}",
          s"-DMILL_TESTNG=${contrib.testng.runClasspath().map(_.path).mkString(",")}",
          s"-DMILL_VERSION=${millVersion()}",
          s"-DMILL_SCALA_LIB=${scalalib.runClasspath().map(_.path).mkString(",")}",
          s"-DMILL_BSP_WORKER=${bsp.worker.runClasspath().map(_.path).mkString(",")}",
          s"-DBSP4J_VERSION=${Deps.bsp4j.dep.version}",
          "-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","),
          "-Djna.nosys=true"
        )
    }

    def testReleaseEnv =
      if (mode == "local") T { Map.empty[String, String] }
      else T { Map("MILL_TEST_RELEASE" -> integration.testMill().path.toString()) }

    def compile = IntegrationTestModule.this.compile()
    def moduleDeps = Seq(IntegrationTestModule.this)
  }
}

trait IntegrationTestCrossModule extends IntegrationTestModule {
  object local extends ModeModule
  object fork extends ModeModule
  object server extends ModeModule
}

def listIn(path: os.Path) = interp.watchValue(os.list(path).map(_.last))

object example extends MillScalaModule {

  def moduleDeps = Seq(integration)

  object basic extends Cross[ExampleCrossModule](listIn(millSourcePath / "basic"): _*)
  object cross extends Cross[ExampleCrossModule](listIn(millSourcePath / "cross"): _*)
  object misc extends Cross[ExampleCrossModule](listIn(millSourcePath / "misc"): _*)
  object web extends Cross[ExampleCrossModule](listIn(millSourcePath / "web"): _*)

  class ExampleCrossModule(val repoSlug: String) extends IntegrationTestCrossModule {
    def testRepoRoot: T[PathRef] = T.source(millSourcePath)
    def compile = example.compile()
    def forkEnv = super.forkEnv() ++ Map("MILL_EXAMPLE_PARSED" -> upickle.default.write(parsed()))
    def parsed = T{
      val states = collection.mutable.Buffer("scala")
      val chunks = collection.mutable.Buffer(collection.mutable.Buffer.empty[String])

      for(line <- os.read.lines(testRepoRoot().path / "build.sc")){
        val (newState, restOpt) = line match{
          case s"/* Example Usage" =>  ("example", None)
          case s"*/" => ("scala", None)
          case s"//$rest" => ("comment", Some(rest.stripPrefix(" ")))
          case l => (if (states.last == "comment") "scala" else states.last, Some(l))
        }

        if (newState != states.last) {
          states.append(newState)
          chunks.append(collection.mutable.Buffer.empty[String])
        }

        restOpt.foreach(r => chunks.last.append(r))
      }

      states.zip(chunks.map(_.mkString("\n").trim)).filter(_._2.nonEmpty)
    }

    def rendered = T{
      os.write(
        T.dest / "example.adoc",
        parsed()
          .filter(_._2.nonEmpty)
          .map {
            case ("scala", txt) =>
              s"""[source,scala]
                 |----
                 |$txt
                 |----""".stripMargin
            case ("comment", txt) => txt
            case ("example", txt) =>
              s"""[source,bash]
                 |----
                 |$txt
                 |----""".stripMargin
          }
          .mkString("\n")
      )
      PathRef(T.dest / "example.adoc")
    }
  }
}

object integration extends MillScalaModule {
  object failure extends Cross[IntegrationCrossModule](listIn(millSourcePath / "failure"): _*)
  object feature extends Cross[IntegrationCrossModule](listIn(millSourcePath / "feature"): _*)
  class IntegrationCrossModule(val repoSlug: String) extends IntegrationTestCrossModule

  def moduleDeps = Seq(scalalib, scalajslib, scalanativelib, runner.test)

  /** Deploy freshly build mill for use in tests */
  def testMill: Target[PathRef] = {
    val name = if (scala.util.Properties.isWin) "mill.bat" else "mill"
    T { PathRef(installLocalTask(binFile = T.task((T.dest / name).toString()))()) }
  }

  // Test of various third-party repositories
  object thirdparty extends MillScalaModule {
    def moduleDeps = Seq(integration)

    object acyclic extends ThirdPartyModule {
      def repoPath = "lihaoyi/acyclic"
      def repoHash = "bc41cd09a287e2c270271e27ccdb3066173a8598"
    }
    object jawn extends ThirdPartyModule {
      def repoPath = "non/jawn"
      def repoHash = "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb"
    }
    object ammonite extends ThirdPartyModule {
      def repoPath = "lihaoyi/Ammonite"
      def repoHash = "26b7ebcace16b4b5b4b68f9344ea6f6f48d9b53e"
    }
    object upickle extends ThirdPartyModule {
      def repoPath = "lihaoyi/upickle"
      def repoHash = "7f33085c890db7550a226c349832eabc3cd18769"
    }
    object caffeine extends ThirdPartyModule {
      def repoPath = "ben-manes/caffeine"
      def repoHash = "c02c623aedded8174030596989769c2fecb82fe4"

      def runClasspath: T[Seq[PathRef]] = T {
        // we need to trigger installation of testng-contrib for Caffeine
        contrib.testng.publishLocal()()
        super.runClasspath()
      }
    }
    trait ThirdPartyModule extends IntegrationTestModule {
      def repoPath: String
      def repoHash: String
      def repoSlug = repoPath.split("/").last
      def testRepoRoot = T {

        shared.downloadTestRepo(repoPath, repoHash, T.dest)
        val wrapperFolder = T.dest / s"$repoSlug-$repoHash"
        os.list(wrapperFolder).foreach(os.move.into(_, T.dest))
        os.remove(wrapperFolder)

        os.list(super.testRepoRoot().path)
          .foreach(os.copy.into(_, T.dest, replaceExisting = true))

        PathRef(T.dest)
      }
      def moduleDeps = super.moduleDeps ++ Seq(thirdparty)
      object local extends ModeModule {
        def runClasspath: T[Seq[PathRef]] = T {
          // we need to trigger installation of testng-contrib for Caffeine
          contrib.testng.publishLocal()()
          super.runClasspath()
        }
      }
      object fork extends ModeModule
      object server extends ModeModule
    }
  }
}

def launcherScript(
    shellJvmArgs: Seq[String],
    cmdJvmArgs: Seq[String],
    shellClassPath: Agg[String],
    cmdClassPath: Agg[String]
) = {
  val millMainClass = "mill.main.client.MillClientMain"
  val millClientMainClass = "mill.main.client.MillClientMain"

  mill.modules.Jvm.universalScript(
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
         |    case "$$1" in
         |      -i | --interactive | --repl | --no-server | --bsp )
         |        init_mill_jvm_opts
         |        ${java(millMainClass, true)}
         |        ;;
         |      *)
         |        ${java(millClientMainClass, false)}
         |        ;;
         |esac
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
         |  ${java(millClientMainClass, false)}
         |)
         |endlocal
         |""".stripMargin
    }
  )
}

object runner extends MillModule {
  override def moduleDeps = Seq(scalalib, scalajslib, scalanativelib, bsp, linenumbers)
  override def skipPreviousVersions: T[Seq[String]] = Seq("0.11.0-M7")

  object linenumbers extends MillPublishModule with MillInternalModule {
    def scalaVersion = Deps.scalaVersion
    override def ivyDeps = Agg(Deps.scalaCompiler(scalaVersion()))
    def testArgs = T {
      Seq(
        s"-DMILL_LINENUMBERS=${runClasspath().map(_.path).mkString(",")}"
      )
    }
  }
}

object dev extends MillModule {
  override def moduleDeps = Seq(runner)

  def forkArgs: T[Seq[String]] =
    (
      scalalib.testArgs() ++
        scalajslib.testArgs() ++
        scalalib.worker.testArgs() ++
        scalanativelib.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        runner.linenumbers.testArgs() ++
        // Workaround for Zinc/JNA bug
        // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
        Seq(
          "-Djna.nosys=true",
          "-DMILL_VERSION=" + publishVersion(),
          "-DMILL_CLASSPATH=" + runClasspath().map(_.path.toString).mkString(",")
        )
    ).distinct

  override def launcher = T {
    val isWin = scala.util.Properties.isWin
    val outputPath = T.ctx.dest / (if (isWin) "run.bat" else "run")

    os.write(outputPath, prependShellScript())

    if (!isWin) {
      os.perms.set(outputPath, "rwxrwxrwx")
    }
    PathRef(outputPath)
  }

  override def extraPublish: T[Seq[PublishInfo]] = T {
    Seq(
      PublishInfo(file = assembly(), classifier = Some("assembly"), ivyConfig = "compile")
    )
  }

  override def assembly = T {
    val isWin = scala.util.Properties.isWin
    val millPath = T.ctx.dest / (if (isWin) "mill.bat" else "mill")
    os.copy(super.assembly().path, millPath)
    PathRef(millPath)
  }

  def prependShellScript = T {
    val (millArgs, otherArgs) =
      forkArgs().partition(arg => arg.startsWith("-DMILL") && !arg.startsWith("-DMILL_VERSION"))
    // Pass Mill options via file, due to small max args limit in Windows
    val vmOptionsFile = T.ctx.dest / "mill.properties"
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
    mill.modules.Jvm.createJar(Agg(), mill.modules.Jvm.JarManifest(manifestEntries))
  }

  def run(args: String*) = T.command {
    args match {
      case Nil => mill.api.Result.Failure("Need to pass in cwd as first argument to dev.run")
      case wd0 +: rest =>
        val wd = os.Path(wd0, T.workspace)
        os.makeDir.all(wd)
        try mill.modules.Jvm.runSubprocess(
            Seq(launcher().path.toString) ++ rest,
            forkEnv(),
            workingDir = wd
          )
        catch { case e => () /*ignore to avoid confusing stacktrace and error messages*/ }
        mill.api.Result.Success(())
    }

  }
}

object docs extends Module {

  /** Generates the mill documentation with Antora. */
  object antora extends Module {
    private val npmExe = if (scala.util.Properties.isWin) "npm.cmd" else "npm"
    private val antoraExe = if (scala.util.Properties.isWin) "antora.cmd" else "antora"
    def npmBase: T[os.Path] = T.persistent { T.dest }
    def prepareAntora(npmDir: os.Path) = {
      Jvm.runSubprocess(
        commandArgs = Seq(
          npmExe,
          "install",
          "@antora/cli@3.0.1",
          "@antora/site-generator-default@3.0.1",
          "gitlab:antora/xref-validator",
          "@antora/lunr-extension@v1.0.0-alpha.6"
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
    def source0: Source = T.source(millSourcePath)
    def source = T{
      os.list(source0().path).foreach(p => os.copy(p, T.dest / p.relativeTo(source0().path)))

      val renderedExamples: Seq[(String, PathRef)] =
        T.traverse(example.basic.items)(t => t._2.rendered.map("example-basic-" + t._1.mkString -> _))() ++
          T.traverse(example.cross.items)(t => t._2.rendered.map("example-cross-" + t._1.mkString -> _))() ++
          T.traverse(example.misc.items)(t => t._2.rendered.map("example-misc-" + t._1.mkString -> _))() ++
          T.traverse(example.web.items)(t => t._2.rendered.map("example-web-" + t._1.mkString -> _))()

      for ((name, pref) <- renderedExamples) {
        os.copy(pref.path, T.dest / "modules" / "ROOT" / "pages" / s"$name.adoc", createFolders = true)
      }

      PathRef(T.dest)
    }
    def supplementalFiles = T.source(millSourcePath / "supplemental-ui")
    def devAntoraSources: Target[PathRef] = T {
      val dest = T.dest
      shared.mycopy(source().path, dest, mergeFolders = true)
      val lines = os.read(dest / "antora.yml").linesIterator.map {
        case l if l.startsWith("version:") =>
          s"version: 'master'" + "\n" + s"display-version: '${millVersion()}'"
        case l if l.startsWith("    mill-version:") =>
          s"    mill-version: '${millVersion()}'"
        case l if l.startsWith("    mill-last-tag:") =>
          s"    mill-last-tag: '${millLastTag()}'"
        case l => l
      }
      os.write.over(dest / "antora.yml", lines.mkString("\n"))
      PathRef(dest)
    }
    def githubPagesPlaybookText(authorMode: Boolean): Task[String] = T.task {
      s"""site:
         |  title: Mill
         |  url: ${Settings.docUrl}
         |  start_page: mill::Intro_to_Mill.adoc
         |
         |content:
         |  sources:
         |    - url: ${if (authorMode) baseDir else Settings.projectUrl}
         |      branches: ${if (Settings.docBranches.isEmpty) "~"
        else Settings.docBranches.map("'" + _ + "'").mkString("[", ",", "]")}
         |      tags: ${Settings.docTags.map("'" + _ + "'").mkString("[", ",", "]")}
         |      start_path: docs/antora
         |    # the master documentation (always in author mode)
         |    - url: ${baseDir}
         |      # edit_url: ${Settings.projectUrl}/edit/{refname}/{path}
         |      branches: HEAD
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
         |    mill-doc-url: ${Settings.docUrl}
         |    utest-github-url: https://github.com/com-lihaoyi/utest
         |    upickle-github-url: https://github.com/com-lihaoyi/upickle
         |
         |antora:
         |  extensions:
         |  - require: '@antora/lunr-extension'
         |    index_latest_only: true
         |
         |""".stripMargin
    }
    def githubPages: Target[PathRef] = T {
      generatePages(authorMode = false)()
    }
    def localPages = T {
      val pages = generatePages(authorMode = true)()
      T.log.outputStream.println(
        s"You can browse the local pages at: ${(pages.path / "index.html").toNIO.toUri()}"
      )
    }
    def generatePages(authorMode: Boolean) = T.task {
      // dependency to sources
      source()
      val docSite = T.dest
      val playbook = docSite / "antora-playbook.yml"
      val siteDir = docSite / "site"
      os.write(
        target = playbook,
        data = githubPagesPlaybookText(authorMode)(),
        createFolders = true
      )
      // check xrefs
      runAntora(
        npmDir = npmBase(),
        workDir = docSite,
        args = Seq(
          "--generator",
          "@antora/xref-validator",
          playbook.last,
          "--to-dir",
          siteDir.toString(),
          "--attribute",
          "page-pagination"
        ) ++
          Seq("--fetch").filter(_ => !authorMode)
      )
      // generate site (we can skip the --fetch now)
      runAntora(
        npmDir = npmBase(),
        workDir = docSite,
        args = Seq(
          playbook.last,
          "--to-dir",
          siteDir.toString(),
          "--attribute",
          "page-pagination"
        )
      )
      os.write(siteDir / ".nojekyll", "")
      // sanitize devAntora source URLs
      sanitizeDevUrls(siteDir, devAntoraSources().path, source().path, baseDir)
      PathRef(siteDir)
    }
//    def htmlCleanerIvyDeps = T{ Agg(ivy"net.sourceforge.htmlcleaner:htmlcleaner:2.24")}
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
}

def assembly = T {
  val version = millVersion()
  val devRunClasspath = dev.runClasspath().map(_.path)
  val filename = if (scala.util.Properties.isWin) "mill.bat" else "mill"
  val commonArgs = Seq(
    "-DMILL_VERSION=" + version,
    // Workaround for Zinc/JNA bug
    // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
    "-Djna.nosys=true"
  )
  val shellArgs = Seq("-DMILL_CLASSPATH=$0") ++ commonArgs
  val cmdArgs = Seq(""""-DMILL_CLASSPATH=%~dpnx0"""") ++ commonArgs
  os.move(
    Jvm.createAssembly(
      devRunClasspath,
      prependShellScript = launcherScript(
        shellArgs,
        cmdArgs,
        Agg("$0"),
        Agg("%~dpnx0")
      )
    ).path,
    T.ctx.dest / filename
  )
  PathRef(T.ctx.dest / filename)
}

def millBootstrap = T.sources(T.workspace / "mill")

def launcher = T {
  val outputPath = T.ctx.dest / "mill"
  val millBootstrapGrepPrefix = "\nDEFAULT_MILL_VERSION="
  os.write(
    outputPath,
    os.read(millBootstrap().head.path)
      .replaceAll(
        millBootstrapGrepPrefix + "[^\\n]+",
        millBootstrapGrepPrefix + millVersion()
      )
  )
  os.perms.set(outputPath, "rwxrwxrwx")
  PathRef(outputPath)
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

  val exampleZips = for{
    exampleBase <- Seq("basic", "cross", "web", "misc")
    examplePath <- os.list(T.workspace / "example" / exampleBase)
  } yield {
    val example = examplePath.subRelativeTo(T.workspace)
    os.copy(examplePath, T.dest / example)
    os.copy(launcher().path, T.dest / example / "mill")
    val exampleStr = example.segments.mkString("-")
    os.proc("zip", "-r", T.dest / s"$exampleStr.zip", T.dest / example).call(cwd = T.dest)
    (T.dest / s"$exampleStr.zip", label + "-" + exampleStr + ".zip")
  }

  val zips = exampleZips ++ Seq(
    (assembly().path, label + "-assembly"),
    (launcher().path, label)
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

def validate(ev: Evaluator): Command[Unit] = T.command {
  T.task(MainModule.evaluateTasks(
    ev.withFailFast(false),
    Seq(
      "__.compile",
      "+",
      "__.mimaReportBinaryIssues",
      "+",
      "mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll",
      "__.sources",
      "+",
      "docs.antora.localPages"
    ),
    selectMode = SelectMode.Separated
  )(identity))()
  ()
}

object DependencyFetchDummy extends ScalaModule {
  override def scalaVersion = Deps.scalaVersion
  override def compileIvyDeps = Agg(
    Deps.semanticDbJava,
    Deps.semanticDB
  )
}
