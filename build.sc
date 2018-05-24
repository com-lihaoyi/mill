import $file.shared
import $file.upload
import java.io.File
import java.nio.file.attribute.PosixFilePermission

import ammonite.ops._
import coursier.maven.MavenRepository
import mill._
import mill.scalalib._
import publish._
import mill.modules.Jvm.createAssembly
import upickle.Js
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

object moduledefs extends MillPublishModule with ScalaModule{
  def scalaVersion = T{ "2.12.6" }
  def ivyDeps = Agg(
    ivy"org.scala-lang:scala-compiler:${scalaVersion()}",
    ivy"com.lihaoyi::sourcecode:0.1.4"
  )
}

trait MillModule extends MillPublishModule with ScalaModule{ outer =>
  def scalaVersion = T{ "2.12.6" }
  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")

  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

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
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.4")
    def testFrameworks = Seq("mill.UTestFramework")
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(moduledefs.jar())
  }
}


object testng extends MillPublishModule{
  def ivyDeps = Agg(
    ivy"org.scala-sbt:test-interface:1.0",
    ivy"org.testng:testng:6.11"
  )
}

object core extends MillModule {
  def moduleDeps = Seq(moduledefs)

  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )

  def ivyDeps = Agg(
    ivy"com.lihaoyi:::ammonite:1.1.2",
    // Necessary so we can share the JNA classes throughout the build process
    ivy"net.java.dev.jna:jna:4.5.0",
    ivy"net.java.dev.jna:jna-platform:4.5.0"
  )

  def generatedSources = T {
    Seq(PathRef(shared.generateCoreSources(T.ctx().dest)))
  }
}

object main extends MillModule {
  def moduleDeps = Seq(core, client)


  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )

  def ivyDeps = Agg(
    ivy"guru.nidi:graphviz-java:0.2.3",
    ivy"org.jgrapht:jgrapht-core:1.2.0"
  )

  def generatedSources = T {
    Seq(PathRef(shared.generateCoreSources(T.ctx().dest)))
  }
  def testArgs = Seq(
    "-DMILL_VERSION=" + build.publishVersion()._2,
  )
  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0){
    def generatedSources = T {
      Seq(PathRef(shared.generateCoreTestSources(T.ctx().dest)))
    }
  }

  object client extends MillPublishModule{
    def ivyDeps = Agg(
      ivy"org.scala-sbt.ipcsocket:ipcsocket:1.0.0".exclude(
        "net.java.dev.jna" -> "jna",
        "net.java.dev.jna" -> "jna-platform"
      )
    )
    object test extends Tests{
      def testFrameworks = Seq("com.novocode.junit.JUnitFramework")
      def ivyDeps = Agg(ivy"com.novocode:junit-interface:0.11")
    }
  }
}


object scalalib extends MillModule {
  def moduleDeps = Seq(main)

  def ivyDeps = Agg(
    ivy"org.scala-sbt:test-interface:1.0"
  )

  def genTask(m: ScalaModule) = T.task{
    Seq(m.jar(), m.sourceJar()) ++
    m.runClasspath()
  }

  def testArgs = T{
    val genIdeaArgs =
      genTask(moduledefs)() ++
      genTask(core)() ++
      genTask(main)() ++
      genTask(scalalib)() ++
      genTask(scalajslib)()

    worker.testArgs() ++
    Seq(
      "-Djna.nosys=true",
      "-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","),
      "-DMILL_SCALA_LIB=" + runClasspath().map(_.path).mkString(",")
    )
  }

  object worker extends MillModule{
    def moduleDeps = Seq(main, scalalib)

    def ivyDeps = Agg(
      ivy"org.scala-sbt::zinc:1.1.5"
    )
    def testArgs = Seq(
      "-DMILL_SCALA_WORKER=" + runClasspath().map(_.path).mkString(",")
    )
  }
}


object scalajslib extends MillModule {

  def moduleDeps = Seq(scalalib)

  def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_BRIDGE_0_6" -> jsbridges("0.6").compile().classes.path,
      "MILL_SCALAJS_BRIDGE_1_0" -> jsbridges("1.0").compile().classes.path
    )
    Seq("-Djna.nosys=true") ++ scalalib.worker.testArgs() ++ (for((k, v) <- mapping.toSeq) yield s"-D$k=$v")
  }

  object jsbridges extends Cross[JsBridgeModule]("0.6", "1.0")
  class JsBridgeModule(scalajsBinary: String) extends MillModule{
    def moduleDeps = Seq(scalajslib)
    def ivyDeps = scalajsBinary match {
      case "0.6" =>
        Agg(
          ivy"org.scala-js::scalajs-tools:0.6.22",
          ivy"org.scala-js::scalajs-sbt-test-adapter:0.6.22",
          ivy"org.scala-js::scalajs-js-envs:0.6.22"
        )
      case "1.0" =>
        Agg(
          ivy"org.scala-js::scalajs-tools:1.0.0-M2",
          ivy"org.scala-js::scalajs-sbt-test-adapter:1.0.0-M2",
          ivy"org.scala-js::scalajs-env-nodejs:1.0.0-M2"
        )
    }
  }
}

def testRepos = T{
  Seq(
    "MILL_ACYCLIC_REPO" ->
      shared.downloadTestRepo("lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", T.ctx().dest/"acyclic"),
    "MILL_JAWN_REPO" ->
      shared.downloadTestRepo("non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", T.ctx().dest/"jawn"),
    "MILL_BETTERFILES_REPO" ->
      shared.downloadTestRepo("pathikrit/better-files", "ba74ae9ef784dcf37f1b22c3990037a4fcc6b5f8", T.ctx().dest/"better-files"),
    "MILL_AMMONITE_REPO" ->
      shared.downloadTestRepo("lihaoyi/ammonite", "96ea548d5e3b72ab6ad4d9765e205bf6cc1c82ac", T.ctx().dest/"ammonite"),
    "MILL_UPICKLE_REPO" ->
      shared.downloadTestRepo("lihaoyi/upickle", "7f33085c890db7550a226c349832eabc3cd18769", T.ctx().dest/"upickle"),
    "MILL_PLAY_JSON_REPO" ->
      shared.downloadTestRepo("playframework/play-json", "0a5ba16a03f3b343ac335117eb314e7713366fd4", T.ctx().dest/"play-json"),
    "MILL_CAFFEINE_REPO" ->
      shared.downloadTestRepo("ben-manes/caffeine", "c02c623aedded8174030596989769c2fecb82fe4", T.ctx().dest/"caffeine")
  )
}

object integration extends MillModule{
  def moduleDeps = Seq(moduledefs, scalalib, scalajslib)
  def testArgs = T{
    scalajslib.testArgs() ++
    scalalib.worker.testArgs() ++
    Seq(
      "-DMILL_TESTNG=" + testng.runClasspath().map(_.path).mkString(","),
      "-DMILL_VERSION=" + build.publishVersion()._2,
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
      def java(mainClass: String) =
        s"""exec java $jvmArgsStr $$JAVA_OPTS -cp "${shellClassPath.mkString(":")}" $mainClass "$$@""""

      s"""case "$$1" in
         |  -i | --interactive )
         |    ${java("mill.MillMain")}
         |    ;;
         |  *)
         |    ${java("mill.main.client.MillClientMain")}
         |    ;;
         |esac""".stripMargin
    },
    cmdCommands = {
      val jvmArgsStr = cmdJvmArgs.mkString(" ")
      def java(mainClass: String) =
        s"""java $jvmArgsStr %JAVA_OPTS% -cp "${cmdClassPath.mkString(";")}" $mainClass %*"""

      s"""if "%1" == "-i" set _I_=true
         |if "%1" == "--interactive" set _I_=true
         |if defined _I_ (
         |  ${java("mill.MillMain")}
         |) else (
         |  ${java("mill.main.client.MillClientMain")}
         |)""".stripMargin
    }
  )
}

object dev extends MillModule{
  def moduleDeps = Seq(scalalib, scalajslib)
  def forkArgs =
    (scalalib.testArgs() ++
     scalajslib.testArgs() ++
     scalalib.worker.testArgs() ++
     // Workaround for Zinc/JNA bug
     // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
     Seq("-Djna.nosys=true", "-DMILL_VERSION=" + build.publishVersion()._2)).distinct

  // Pass dev.assembly VM options via file in Window due to small max args limit
  def windowsVmOptions(taskName: String, batch: Path, args: Seq[String])(implicit ctx: mill.util.Ctx) = {
    if (System.getProperty("java.specification.version").startsWith("1.")) {
      throw new Error(s"$taskName in Windows is only supported using Java 9 or above")
    }
    val vmOptionsFile = T.ctx().dest / "mill.vmoptions"
    T.ctx().log.info(s"Generated $vmOptionsFile; it should be kept in the same directory as $taskName's ${batch.last}")
    write(vmOptionsFile, args.mkString("\r\n"))
  }

  def launcher = T{
    val isWin = scala.util.Properties.isWin
    val outputPath = T.ctx().dest / (if (isWin) "run.bat" else "run")

    write(outputPath, prependShellScript())

    if (isWin) {
      windowsVmOptions("dev.launcher", outputPath, forkArgs())
    } else {
      val perms = java.nio.file.Files.getPosixFilePermissions(outputPath.toNIO)
      perms.add(PosixFilePermission.GROUP_EXECUTE)
      perms.add(PosixFilePermission.OWNER_EXECUTE)
      perms.add(PosixFilePermission.OTHERS_EXECUTE)
      java.nio.file.Files.setPosixFilePermissions(outputPath.toNIO, perms)
    }
    PathRef(outputPath)
  }

  def assembly = T{
    val isWin = scala.util.Properties.isWin
    val millPath = T.ctx().dest / (if (isWin) "mill.bat" else "mill")
    mv(super.assembly().path, millPath)
    if (isWin) windowsVmOptions("dev.launcher", millPath, forkArgs())
    PathRef(millPath)
  }

  def prependShellScript = T{
    val classpath = runClasspath().map(_.path.toString)
    val args = forkArgs().distinct
    val (shellArgs, cmdArgs) =
      if (!scala.util.Properties.isWin) (
        Seq("-DMILL_CLASSPATH=" + classpath.mkString(":")) ++ args,
        Seq("-DMILL_CLASSPATH=" + classpath.mkString(";")) ++ args
      )
      else (
        Seq("""-XX:VMOptionsFile="$( dirname "$0" )"/mill.vmoptions"""),
        Seq("""-XX:VMOptionsFile=%~dp0\mill.vmoptions""")
      )
    launcherScript(shellArgs, cmdArgs, classpath, classpath)
  }

  def run(args: String*) = T.command{
    args match{
      case Nil => mill.eval.Result.Failure("Need to pass in cwd as first argument to dev.run")
      case wd0 +: rest =>
        val wd = Path(wd0, pwd)
        mkdir(wd)
        mill.modules.Jvm.baseInteractiveSubprocess(
          Seq(launcher().path.toString) ++ rest,
          forkEnv(),
          workingDir = wd
        )
        mill.eval.Result.Success(())
    }

  }
}

def release = T{
  val dest = T.ctx().dest
  val filename = if (scala.util.Properties.isWin) "mill.bat" else "mill"
  val args = Seq(
    "-DMILL_CLASSPATH=$0",
    "-DMILL_VERSION=" + publishVersion()._2,
    // Workaround for Zinc/JNA bug
    // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
    "-Djna.nosys=true"
  )
  mv(
    createAssembly(
      dev.runClasspath().map(_.path),
      prependShellScript = launcherScript(
        args,
        args,
        Agg("$0"),
        Agg("%~dpnx0")
      )
    ).path,
    dest / filename
  )
  PathRef(dest / filename)
}

val isMasterCommit = {
  sys.env.get("TRAVIS_PULL_REQUEST") == Some("false") &&
  (sys.env.get("TRAVIS_BRANCH") == Some("master") || sys.env("TRAVIS_TAG") != "")
}

def gitHead = T.input{
  sys.env.get("TRAVIS_COMMIT").getOrElse(
    %%('git, "rev-parse", "HEAD")(pwd).out.string.trim()
  )
}

def publishVersion = T.input{
  val tag =
    try Option(
      %%('git, 'describe, "--exact-match", "--tags", gitHead())(pwd).out.string.trim()
    )
    catch{case e => None}

  val dirtySuffix = %%('git, 'diff)(pwd).out.string.trim() match{
    case "" => ""
    case s => "-DIRTY" + Integer.toHexString(s.hashCode)
  }

  tag match{
    case Some(t) => (t, t)
    case None =>
      val latestTaggedVersion = %%('git, 'describe, "--abbrev=0", "--tags")(pwd).out.trim

      val commitsSinceLastTag =
        %%('git, "rev-list", gitHead(), "--not", latestTaggedVersion, "--count")(pwd).out.trim.toInt

      (latestTaggedVersion, s"$latestTaggedVersion-$commitsSinceLastTag-${gitHead().take(6)}$dirtySuffix")
  }
}

def uploadToGithub(authKey: String) = T.command{
  val (releaseTag, label) = publishVersion()

  if (releaseTag == label){
    scalaj.http.Http("https://api.github.com/repos/lihaoyi/mill/releases")
      .postData(
        ujson.write(
          Js.Obj(
            "tag_name" -> releaseTag,
            "name" -> releaseTag
          )
        )
      )
      .header("Authorization", "token " + authKey)
      .asString
  }

  upload.apply(release().path, releaseTag, label, authKey)
}
