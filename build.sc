import $file.ci.shared
import $file.ci.upload
import java.nio.file.attribute.PosixFilePermission
import $ivy.`org.scalaj::scalaj-http:2.4.2`

import coursier.maven.MavenRepository
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.modules.Jvm.createAssembly

object Deps {

  object Scalajs_0_6 {
    val scalajsJsEnvs =  ivy"org.scala-js::scalajs-js-envs:0.6.32"
    val scalajsSbtTestAdapter =  ivy"org.scala-js::scalajs-sbt-test-adapter:0.6.32"
    val scalajsTools = ivy"org.scala-js::scalajs-tools:0.6.32"
  }

  object Scalajs_1 {
    val scalajsEnvJsdomNodejs =  ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0"
    val scalajsEnvNodejs =  ivy"org.scala-js::scalajs-env-nodejs:1.1.1"
    val scalajsEnvPhantomjs =  ivy"org.scala-js::scalajs-env-phantomjs:1.0.0"
    val scalajsSbtTestAdapter = ivy"org.scala-js::scalajs-sbt-test-adapter:1.1.1"
    val scalajsLinker = ivy"org.scala-js::scalajs-linker:1.1.1"
  }

  object Scalanative_0_3 {
    val scalanativeTools = ivy"org.scala-native::tools:0.3.9"
    val scalanativeUtil = ivy"org.scala-native::util:0.3.9"
    val scalanativeNir = ivy"org.scala-native::nir:0.3.9"
    val scalanativeTestRunner = ivy"org.scala-native::test-runner:0.3.9"
  }

  object Scalanative_0_4 {
    val scalanativeTools = ivy"org.scala-native::tools:0.4.0-M2"
    val scalanativeUtil = ivy"org.scala-native::util:0.4.0-M2"
    val scalanativeNir = ivy"org.scala-native::nir:0.4.0-M2"
    val scalanativeTestRunner = ivy"org.scala-native::test-runner:0.4.0-M2"
  }

  val acyclic = ivy"com.lihaoyi::acyclic:0.2.0"
  val ammonite = ivy"com.lihaoyi:::ammonite:2.2.0"
  val scalametaTrees = ivy"org.scalameta::trees:4.3.7"
  val bloopConfig = ivy"ch.epfl.scala::bloop-config:1.4.0-RC1"
  val coursier = ivy"io.get-coursier::coursier:2.0.0-RC6-15"
  val flywayCore = ivy"org.flywaydb:flyway-core:6.0.1"
  val graphvizJava = ivy"guru.nidi:graphviz-java:0.8.3"
  val ipcsocket = ivy"org.scala-sbt.ipcsocket:ipcsocket:1.0.0"
  val ipcsocketExcludingJna = ipcsocket.exclude(
    "net.java.dev.jna" -> "jna",
    "net.java.dev.jna" -> "jna-platform"
  )
  val javaxServlet = ivy"org.eclipse.jetty.orbit:javax.servlet:3.0.0.v201112011016"
  val jettyServer = ivy"org.eclipse.jetty:jetty-server:8.1.16.v20140903"
  val jettyWebsocket =  ivy"org.eclipse.jetty:jetty-websocket:8.1.16.v20140903"
  val jgraphtCore = ivy"org.jgrapht:jgrapht-core:1.3.0"

  val jna = ivy"net.java.dev.jna:jna:5.0.0"
  val jnaPlatform = ivy"net.java.dev.jna:jna-platform:5.0.0"

  val junitInterface = ivy"com.novocode:junit-interface:0.11"
  val lambdaTest = ivy"de.tototec:de.tobiasroeser.lambdatest:0.7.0"
  val osLib = ivy"com.lihaoyi::os-lib:0.7.1"
  val testng = ivy"org.testng:testng:6.11"
  val sbtTestInterface = ivy"org.scala-sbt:test-interface:1.0"
  def scalaCompiler(scalaVersion: String) = ivy"org.scala-lang:scala-compiler:${scalaVersion}"
  val scalafmtDynamic = ivy"org.scalameta::scalafmt-dynamic:2.2.1"
  def scalaReflect(scalaVersion: String) = ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  def scalacScoveragePlugin = ivy"org.scoverage::scalac-scoverage-plugin:1.4.1"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.2.1"
  val ujsonCirce = ivy"com.lihaoyi::ujson-circe:1.2.0"
  val upickle = ivy"com.lihaoyi::upickle:1.2.0"
  val utest = ivy"com.lihaoyi::utest:0.7.4"
  val zinc = ivy"org.scala-sbt::zinc:1.4.0-M1"
  val bsp = ivy"ch.epfl.scala:bsp4j:2.0.0-M4"
  val jarjarabrams = ivy"com.eed3si9n.jarjarabrams::jarjar-abrams-core:0.3.0"
}

trait MillPublishModule extends PublishModule{

  def artifactName = "mill-" + super.artifactName()
  def publishVersion = build.publishVersion()._2

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/mill",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "mill"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )

  def javacOptions = Seq("-source", "1.8", "-target", "1.8")
}
trait MillApiModule extends MillPublishModule with ScalaModule{
  def scalaVersion = T{ "2.13.2" }
  def compileIvyDeps = Agg(Deps.acyclic)
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(Deps.acyclic)
  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )
}
trait MillModule extends MillApiModule { outer =>
  def scalaVersion = T{ "2.13.2" }
  def scalacPluginClasspath =
    super.scalacPluginClasspath() ++ Seq(main.moduledefs.jar())

  def testArgs = T{ Seq.empty[String] }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends mill.Module()(ctx0) with super.Tests{
    def repositories = super.repositories ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/releases")
    )
    def forkArgs = T{ testArgs() }
    def moduleDeps =
      if (this == main.test) Seq(main)
      else Seq(outer, main.test)
    def ivyDeps = Agg(Deps.utest)
    def testFrameworks = Seq("mill.UTestFramework")
    def scalacPluginClasspath =
      super.scalacPluginClasspath() ++ Seq(main.moduledefs.jar())
  }
}


object main extends MillModule {
  def moduleDeps = Seq(core, client)

  def compileIvyDeps = Agg(
    Deps.scalaReflect(scalaVersion())
  )

  def generatedSources = T {
    Seq(PathRef(shared.generateCoreSources(T.ctx.dest)))
  }
  def testArgs = Seq(
    "-DMILL_VERSION=" + build.publishVersion()._2,
  )
  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0){
    def generatedSources = T {
      Seq(PathRef(shared.generateCoreTestSources(T.ctx.dest)))
    }
  }
  object api extends MillApiModule {
    def ivyDeps = Agg(
      Deps.osLib,
      Deps.upickle,
      Deps.sbtTestInterface
    )
  }
  object core extends MillModule {
    def moduleDeps = Seq(moduledefs, api)

    def compileIvyDeps = Agg(
      Deps.scalaReflect(scalaVersion())
    )

    def ivyDeps = Agg(
      Deps.ammonite,
      Deps.scalametaTrees.forceVersion(),
      Deps.coursier,
      // Necessary so we can share the JNA classes throughout the build process
      Deps.jna,
      Deps.jnaPlatform,
      Deps.coursier,
      Deps.jarjarabrams
    )

    def generatedSources = T {
      val dest = T.ctx.dest
      writeBuildInfo(dest, scalaVersion(), publishVersion())
      shared.generateCoreSources(dest)
      Seq(PathRef(dest))
    }

    def writeBuildInfo(dir : os.Path, scalaVersion: String, millVersion: String) = {
      val code = s"""
        |package mill
        |
        |object BuildInfo {
        |  val scalaVersion = "$scalaVersion"
        |  val millVersion = "$millVersion"
        |}
      """.stripMargin.trim

      os.write(dir / "BuildInfo.scala", code)
    }
  }

  object moduledefs extends MillPublishModule with ScalaModule{
    def scalaVersion = T{ "2.13.2" }
    def ivyDeps = Agg(
      Deps.scalaCompiler(scalaVersion()),
      Deps.sourcecode,
    )
  }

  object client extends MillPublishModule{
    def ivyDeps = Agg(
      Deps.ipcsocketExcludingJna
    )
    object test extends Tests {
      def testFrameworks = Seq("com.novocode.junit.JUnitFramework")
      def ivyDeps = T{ Agg(
        Deps.junitInterface,
        Deps.lambdaTest
      )}
    }
  }

  object graphviz extends MillModule{
    def moduleDeps = Seq(main, scalalib)

    def ivyDeps = Agg(
      Deps.graphvizJava,
      Deps.jgraphtCore
    )
    def testArgs = Seq(
      "-DMILL_GRAPHVIZ=" + runClasspath().map(_.path).mkString(",")
    )
  }
}


object scalalib extends MillModule {
  def moduleDeps = Seq(main, scalalib.api)

  def ivyDeps = Agg(
    Deps.sbtTestInterface,
    Deps.scalafmtDynamic
  )

  def genTask(m: ScalaModule) = T.task{
    Seq(m.jar(), m.sourceJar()) ++
    m.runClasspath()
  }

  override def generatedSources = T{
    val dest = T.ctx.dest
    os.write(dest / "Versions.scala",
      s"""package mill.scalalib
        |
        |/**
        | * Dependency versions.
        | * Generated from mill in build.sc.
        | */
        |object Versions {
        |  /** Version of Ammonite. */
        |  val ammonite = "${Deps.ammonite.dep.version}"
        |  /** Version of Zinc. */
        |  val zinc = "${Deps.zinc.dep.version}"
        |}
        |
        |""".stripMargin)
    super.generatedSources() ++ Seq(PathRef(dest))
  }

  def testArgs = T{
    val genIdeaArgs =
      genTask(main.moduledefs)() ++
      genTask(main.core)() ++
      genTask(main)() ++
      genTask(scalalib)() ++
      genTask(scalajslib)() ++
      genTask(scalanativelib)()

    worker.testArgs() ++
    main.graphviz.testArgs() ++
    Seq(
      "-Djna.nosys=true",
      "-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","),
      "-DMILL_SCALA_LIB=" + runClasspath().map(_.path).mkString(",")
    )
  }
  object backgroundwrapper extends MillPublishModule{
    def ivyDeps = Agg(
      Deps.sbtTestInterface
    )
    def testArgs = T{
      Seq(
        "-DMILL_BACKGROUNDWRAPPER=" + runClasspath().map(_.path).mkString(",")
      )
    }
  }
  object api extends MillApiModule {
    def moduleDeps = Seq(main.api)
  }
  object worker extends MillApiModule {

    def moduleDeps = Seq(scalalib.api)

    def ivyDeps = Agg(
      Deps.zinc
    )
    def testArgs = T{Seq(
      "-DMILL_SCALA_WORKER=" + runClasspath().map(_.path).mkString(",")
    )}
  }
}


object scalajslib extends MillModule {

  def moduleDeps = Seq(scalalib, scalajslib.api)

  def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_WORKER_0_6" -> worker("0.6").compile().classes.path,
      "MILL_SCALAJS_WORKER_1" -> worker("1").compile().classes.path
    )
    Seq("-Djna.nosys=true") ++
    scalalib.worker.testArgs() ++
    scalalib.backgroundwrapper.testArgs() ++
    (for((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
  }

  object api extends MillApiModule {
    def moduleDeps = Seq(main.api)
    def ivyDeps = Agg(Deps.sbtTestInterface)
  }
  object worker extends Cross[WorkerModule]("0.6", "1")
  class WorkerModule(scalajsWorkerVersion: String) extends MillApiModule{
    def moduleDeps = Seq(scalajslib.api)
    def ivyDeps = scalajsWorkerVersion match {
      case "0.6" =>
        Agg(
          Deps.Scalajs_0_6.scalajsTools,
          Deps.Scalajs_0_6.scalajsSbtTestAdapter,
          Deps.Scalajs_0_6.scalajsJsEnvs,
          Deps.jettyWebsocket,
          Deps.jettyServer,
          Deps.javaxServlet
        )
      case "1" =>
        Agg(
          Deps.Scalajs_1.scalajsLinker,
          Deps.Scalajs_1.scalajsSbtTestAdapter,
          Deps.Scalajs_1.scalajsEnvNodejs,
          Deps.Scalajs_1.scalajsEnvJsdomNodejs,
          Deps.Scalajs_1.scalajsEnvPhantomjs,
          Deps.jettyWebsocket,
          Deps.jettyServer,
          Deps.javaxServlet
        )
    }
  }
}


object contrib extends MillModule {
  object testng extends MillPublishModule{
    def ivyDeps = Agg(
      Deps.sbtTestInterface,
      Deps.testng
    )
  }

  object twirllib extends MillModule {
    def moduleDeps = Seq(scalalib)
  }

  object playlib extends MillModule {
    def moduleDeps = Seq(scalalib, twirllib, playlib.api)

    def testArgs = T {
      val mapping = Map(
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_6" -> worker("2.6").assembly().path,
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_7" -> worker("2.7").assembly().path
      )

      scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        (for ((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
    }

    object api extends MillPublishModule {

    }
    object worker extends Cross[WorkerModule]( "2.6", "2.7")

    class WorkerModule(scalajsBinary: String) extends MillApiModule  {
      def scalaVersion = T { "2.12.10" }
      def moduleDeps = Seq(playlib.api)
      def ivyDeps = scalajsBinary match {
        case  "2.6"=>
          Agg(
            Deps.osLib,
            ivy"com.typesafe.play::routes-compiler::2.6.0"
          )
        case "2.7" =>
          Agg(
            Deps.osLib,
            ivy"com.typesafe.play::routes-compiler::2.7.0"
          )
      }
    }

  }

  object scalapblib extends MillModule {
    def moduleDeps = Seq(scalalib)
  }

  object scoverage extends MillModule {
    object api extends MillApiModule {
      def moduleDeps = Seq(main.api)
    }

    def moduleDeps = Seq(scalalib, scoverage.api)

    def testArgs = T {
      val mapping = Map(
        "MILL_SCOVERAGE_REPORT_WORKER" -> worker.compile().classes.path
      )
      scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        (for ((k, v) <- mapping) yield s"-D$k=$v")
    }

    // So we can test with buildinfo in the classpath
    val test = new Tests(implicitly)
    class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0) {
      override def moduleDeps = super.moduleDeps :+ contrib.buildinfo
    }

    object worker extends MillApiModule {
      def moduleDeps = Seq(scoverage.api)
      def compileIvyDeps = T{
        // compile-time only, need to provide the correct scoverage version runtime
        Agg(Deps.scalacScoveragePlugin)
      }
    }
  }

  object buildinfo extends MillModule {
    def moduleDeps = Seq(scalalib)
    // why do I need this?
    def testArgs = T{
      Seq("-Djna.nosys=true") ++
      scalalib.worker.testArgs() ++
      scalalib.backgroundwrapper.testArgs()
    }
   }

  object tut extends MillModule {
    def moduleDeps = Seq(scalalib)
    def testArgs = T{
      scalalib.worker.testArgs() ++
      scalalib.backgroundwrapper.testArgs()
    }
  }

  object flyway extends MillModule {
    def moduleDeps = Seq(scalalib)
    def ivyDeps = Agg(Deps.flywayCore)
  }


  object docker extends MillModule {
    def moduleDeps = Seq(scalalib)
  }

  object bloop extends MillModule {
    def moduleDeps = Seq(scalalib, scalajslib, scalanativelib)
    def ivyDeps = Agg(
      Deps.bloopConfig
    )
    def testArgs = T(scalanativelib.testArgs())
  }

  object bsp extends MillModule {

    def moduleDeps = Seq(scalalib, scalajslib, main, scalanativelib)
    def ivyDeps = Agg(
      Deps.bsp,
      Deps.ujsonCirce,
      Deps.sbtTestInterface
    )
  }

  object artifactory extends MillModule {
    def moduleDeps = Seq(scalalib)
  }

  object versionfile extends MillModule {
    def moduleDeps = Seq(scalalib)
  }

  object bintray extends MillModule {
    def moduleDeps = Seq(scalalib)
  }

}


object scalanativelib extends MillModule {
  def moduleDeps = Seq(scalalib, scalanativelib.api)

  def scalacOptions = Seq[String]() // disable -P:acyclic:force

  def testArgs = T{
    val mapping = Map(
      "MILL_SCALANATIVE_WORKER_0_3" -> worker("0.3").assembly().path,
      "MILL_SCALANATIVE_WORKER_0_4" -> worker("0.4").assembly().path
    )
    scalalib.worker.testArgs() ++
    scalalib.backgroundwrapper.testArgs() ++
    (for((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
  }
  object api extends MillPublishModule {
    def ivyDeps = Agg(Deps.sbtTestInterface)
  }
  object worker extends Cross[WorkerModule]("0.3", "0.4")
    class WorkerModule(scalaNativeWorkerVersion: String) extends MillApiModule {
    def scalaVersion = T{ "2.12.10" }
    override def millSourcePath(): os.Path = super.millSourcePath / os.up
    def moduleDeps = Seq(scalanativelib.api)
    def ivyDeps = scalaNativeWorkerVersion match {
      case "0.3" =>
        Agg(
          Deps.osLib,
          Deps.Scalanative_0_3.scalanativeTools,
          Deps.Scalanative_0_3.scalanativeUtil,
          Deps.Scalanative_0_3.scalanativeNir,
          Deps.Scalanative_0_3.scalanativeTestRunner
        )
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

def testRepos = T{
  Seq(
    "MILL_ACYCLIC_REPO" ->
      shared.downloadTestRepo("lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", T.ctx.dest/"acyclic"),
    "MILL_JAWN_REPO" ->
      shared.downloadTestRepo("non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", T.ctx.dest/"jawn"),
    "MILL_BETTERFILES_REPO" ->
      shared.downloadTestRepo("pathikrit/better-files", "ba74ae9ef784dcf37f1b22c3990037a4fcc6b5f8", T.ctx.dest/"better-files"),
    "MILL_AMMONITE_REPO" ->
      shared.downloadTestRepo("lihaoyi/ammonite", "26b7ebcace16b4b5b4b68f9344ea6f6f48d9b53e", T.ctx.dest/"ammonite"),
    "MILL_UPICKLE_REPO" ->
      shared.downloadTestRepo("lihaoyi/upickle", "7f33085c890db7550a226c349832eabc3cd18769", T.ctx.dest/"upickle"),
    "MILL_PLAY_JSON_REPO" ->
      shared.downloadTestRepo("playframework/play-json", "0a5ba16a03f3b343ac335117eb314e7713366fd4", T.ctx.dest/"play-json"),
    "MILL_CAFFEINE_REPO" ->
      shared.downloadTestRepo("ben-manes/caffeine", "c02c623aedded8174030596989769c2fecb82fe4", T.ctx.dest/"caffeine")
  )
}

object integration extends MillModule {
  def moduleDeps = Seq(main.moduledefs, scalalib, scalajslib, scalanativelib)
  def testArgs = T{
    scalajslib.testArgs() ++
    scalalib.worker.testArgs() ++
    scalalib.backgroundwrapper.testArgs() ++
    scalanativelib.testArgs() ++
    Seq(
      "-DMILL_TESTNG=" + contrib.testng.runClasspath().map(_.path).mkString(","),
      "-DMILL_VERSION=" + build.publishVersion()._2,
      "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(","),
      "-Djna.nosys=true"
    ) ++
    (for((k, v) <- testRepos()) yield s"-D$k=$v")
  }
  def forkArgs = testArgs()
}


def launcherScript(shellJvmArgs: Seq[String],
                   cmdJvmArgs: Seq[String],
                   shellClassPath: Agg[String],
                   cmdClassPath: Agg[String]) = {
  mill.modules.Jvm.universalScript(
    shellCommands = {
      val jvmArgsStr = shellJvmArgs.mkString(" ")
      def java(mainClass: String, passMillJvmOpts: Boolean) = {
        val millJvmOpts = if (passMillJvmOpts) "$mill_jvm_opts" else ""
        s"""exec $$JAVACMD $jvmArgsStr $$JAVA_OPTS $millJvmOpts -cp "${shellClassPath.mkString(":")}" $mainClass "$$@""""
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
         |    while IFS= read line
         |    do
         |      case $$line in
         |        "-X"*) mill_jvm_opts="$${mill_jvm_opts} $$line"
         |      esac
         |    done <"$$mill_jvm_opts_file"
         |  fi
         |}
         |
         |# Client-server mode doesn't seem to work on WSL, just disable it for now
         |# https://stackoverflow.com/a/43618657/871202
         |if grep -qEi "(Microsoft|WSL)" /proc/version > /dev/null 2> /dev/null ; then
         |    init_mill_jvm_opts
         |    COURSIER_CACHE=.coursier ${java("mill.MillMain", true)}
         |else
         |    case "$$1" in
         |      -i | --interactive | --repl | --no-server )
         |        init_mill_jvm_opts
         |        ${java("mill.MillMain", true)}
         |        ;;
         |      *)
         |        ${java("mill.main.client.MillClientMain", false)}
         |        ;;
         |esac
         |fi
         |""".stripMargin
    },
    cmdCommands = {
      val jvmArgsStr = cmdJvmArgs.mkString(" ")
      def java(mainClass: String, passMillJvmOpts: Boolean) = {
        val millJvmOpts = if (passMillJvmOpts) "!mill_jvm_opts!" else ""
        s""""%JAVACMD%" $jvmArgsStr %JAVA_OPTS% $millJvmOpts -cp "${cmdClassPath.mkString(";")}" $mainClass %*"""
      }

      s"""setlocal EnableDelayedExpansion
         |set "JAVACMD=java.exe"
         |if not "%JAVA_HOME%"=="" set "JAVACMD=%JAVA_HOME%\\bin\\java.exe"
         |if "%1" == "-i" set _I_=true
         |if "%1" == "--interactive" set _I_=true
         |if "%1" == "--repl" set _I_=true
         |if "%1" == "--no-server" set _I_=true
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
         |  ${java("mill.MillMain", true)}
         |) else (
         |  ${java("mill.main.client.MillClientMain", false)}
         |)
         |endlocal
         |""".stripMargin
    }
  )
}

object dev extends MillModule{
  def moduleDeps = Seq(scalalib, scalajslib, scalanativelib)


  def forkArgs =
    (
      scalalib.testArgs() ++
      scalajslib.testArgs() ++
      scalalib.worker.testArgs() ++
      scalanativelib.testArgs() ++
      scalalib.backgroundwrapper.testArgs() ++
      // Workaround for Zinc/JNA bug
      // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
      Seq(
        "-Djna.nosys=true",
        "-DMILL_VERSION=" + build.publishVersion()._2,
        "-DMILL_CLASSPATH=" + runClasspath().map(_.path.toString).mkString(",")
      )
    ).distinct

  def launcher = T{
    val isWin = scala.util.Properties.isWin
    val outputPath = T.ctx.dest / (if (isWin) "run.bat" else "run")

    os.write(outputPath, prependShellScript())

    if (!isWin) {
      os.perms.set(outputPath, "rwxrwxrwx")
    }
    PathRef(outputPath)
  }

  override def extraPublish: T[Seq[PublishInfo]] = T{ Seq(
    PublishInfo(file = assembly(), classifier = Some("assembly"), ivyConfig = "compile")
  )}

  def assembly = T{
    val isWin = scala.util.Properties.isWin
    val millPath = T.ctx.dest / (if (isWin) "mill.bat" else "mill")
    os.move(super.assembly().path, millPath)
    PathRef(millPath)
  }

  def prependShellScript = T{
    val (millArgs, otherArgs) = forkArgs().partition(arg => arg.startsWith("-DMILL") && !arg.startsWith("-DMILL_VERSION"))
    // Pass Mill options via file, due to small max args limit in Windows
    val vmOptionsFile = T.ctx.dest / "mill.properties"
    val millOptionsContent = millArgs.map(_.drop(2).replace("\\", "/")).mkString("\r\n") // drop -D prefix, replace \ with /
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

  def pathingJar = T{
    // see http://todayguesswhat.blogspot.com/2011/03/jar-manifestmf-class-path-referencing.html
    // for more detailed explanation
    val isWin = scala.util.Properties.isWin
    val classpath = runClasspath().map{ pathRef =>
      val path = if (isWin) "/" + pathRef.path.toString.replace("\\", "/")
                 else pathRef.path.toString
      if (path.endsWith(".jar")) path
      else path + "/"
    }.mkString(" ")
    val manifestEntries = Map[String,String](
        java.util.jar.Attributes.Name.MANIFEST_VERSION.toString -> "1.0",
        "Created-By" -> "Scala mill",
        "Class-Path" -> classpath
      )
    mill.modules.Jvm.createJar(Agg(), mill.modules.Jvm.JarManifest(manifestEntries))
  }

  def run(args: String*) = T.command{
    args match{
      case Nil => mill.eval.Result.Failure("Need to pass in cwd as first argument to dev.run")
      case wd0 +: rest =>
        val wd = os.Path(wd0, os.pwd)
        os.makeDir.all(wd)
        mill.modules.Jvm.baseInteractiveSubprocess(
          Seq(launcher().path.toString) ++ rest,
          forkEnv(),
          workingDir = wd
        )
        mill.eval.Result.Success(())
    }

  }
}

object docs extends Module {
  /** Download ammonite. */
  def ammoniteVersion: String = "1.4.0"
  def ammonite: T[PathRef] = T.persistent {
    val dest = T.dest / s"ammonite-${ammoniteVersion}"
    if(!os.isFile(dest)) {
      val download = mill.modules.Util.download(
        s"https://github.com/lihaoyi/Ammonite/releases/download/${ammoniteVersion}/2.12-${ammoniteVersion}",
        os.rel / s"ammonite-${ammoniteVersion}.part"
      )
      os.move(download.path, dest)
    }
    os.perms.set(dest, os.perms(dest) + PosixFilePermission.OWNER_EXECUTE)
    PathRef(dest)
  }
  def sources = T.sources(millSourcePath)
  /** Generate the documentation site. */
  def generate = T{
    sources()
    val dest = T.dest / "site"
    mill.modules.Jvm.runSubprocess(
      commandArgs = Seq(ammonite().path.toString(), "build.sc", "--targetDir", dest.toString()),
      envArgs = Map(),
      workingDir = millSourcePath
    )
    PathRef(dest)
  }
}

def assembly = T{

  val version = publishVersion()._2
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
    createAssembly(
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

def millBootstrap = T.sources(os.pwd / "mill")

def launcher = T{
  val outputPath = T.ctx.dest / "mill"
  val millBootstrapGrepPrefix = "\nDEFAULT_MILL_VERSION="
  os.write(
    outputPath,
    os.read(millBootstrap().head.path)
      .replaceAll(
        millBootstrapGrepPrefix + "[^\\n]+",
        millBootstrapGrepPrefix + publishVersion()._2
      )
  )
  os.perms.set(outputPath, "rwxrwxrwx")
  PathRef(outputPath)
}

val isMasterCommit = {
  sys.env.get("TRAVIS_PULL_REQUEST") == Some("false") &&
  (sys.env.get("TRAVIS_BRANCH") == Some("master") || sys.env("TRAVIS_TAG") != "")
}

def gitHead = T.input{
  sys.env.get("TRAVIS_COMMIT").getOrElse(
    os.proc('git, "rev-parse", "HEAD").call().out.trim
  )
}

def publishVersion = T.input{
  val tag =
    try Option(
      os.proc('git, 'describe, "--exact-match", "--tags", "--always", gitHead()).call().out.trim
    )
    catch{case e => None}

  val dirtySuffix = os.proc('git, 'diff).call().out.trim match{
    case "" => ""
    case s => "-DIRTY" + Integer.toHexString(s.hashCode)
  }

  tag match{
    case Some(t) => (t, t)
    case None =>
      val latestTaggedVersion = os.proc('git, 'describe, "--abbrev=0", "--always", "--tags").call().out.trim

      val commitsSinceLastTag =
        os.proc('git, "rev-list", gitHead(), "--not", latestTaggedVersion, "--count").call().out.trim.toInt

      (latestTaggedVersion, s"$latestTaggedVersion-$commitsSinceLastTag-${gitHead().take(6)}$dirtySuffix")
  }
}

def uploadToGithub(authKey: String) = T.command{
  val (releaseTag, label) = publishVersion()

  if (releaseTag == label){
    scalaj.http.Http("https://api.github.com/repos/lihaoyi/mill/releases")
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

  for(example <- Seq("example-1", "example-2", "example-3")) {
    os.copy(os.pwd / "example" / example, T.dest / example)
    os.copy(launcher().path, T.dest / example / "mill")
    os.proc('zip, "-r", T.dest / s"$example.zip", example).call(cwd = T.dest)
    upload.apply(T.dest / s"$example.zip", releaseTag, label + "-" + example + ".zip", authKey)
  }
  upload.apply(assembly().path, releaseTag, label + "-assembly", authKey)

  upload.apply(launcher().path, releaseTag, label, authKey)
}
