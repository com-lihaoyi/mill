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
  def scalaVersion = "2.12.4"
  def artifactName = "mill-" + super.artifactName()
  def publishVersion = build.publishVersion()._2

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/mill",
    licenses = Seq(
      License("MIT license", "http://www.opensource.org/licenses/mit-license.php")
    ),
    // TODO 0.1.4
    // licenses = Seq(License.MIT),
    scm = SCM(
      "git://github.com/lihaoyi/mill.git",
      "scm:git://github.com/lihaoyi/mill.git"
    ),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )
}
object moduledefs extends MillPublishModule{
  def ivyDeps = Agg(
    ivy"org.scala-lang:scala-compiler:${scalaVersion()}",
    ivy"com.lihaoyi::sourcecode:0.1.4"
  )
}

trait MillModule extends MillPublishModule{ outer =>

  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")

  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  def testArgs = T{ Seq.empty[String] }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends mill.Module()(ctx0) with super.Tests{
    def forkArgs = T{ testArgs() }
    def moduleDeps =
      if (this == main.test) Seq(main)
      else Seq(outer, main.test)
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFrameworks = Seq("mill.UTestFramework")
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(moduledefs.jar())
  }
}

object clientserver extends MillModule{
  def ivyDeps = Agg(
    ivy"org.scala-sbt.ipcsocket:ipcsocket:1.0.0"
  )
  val test = new Tests(implicitly)
}

object core extends MillModule {
  def moduleDeps = Seq(moduledefs)

  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )

  def ivyDeps = Agg(
    ivy"com.lihaoyi::sourcecode:0.1.4",
    ivy"com.lihaoyi:::ammonite:1.0.3-49-7fa03d0",
    ivy"jline:jline:2.14.5"
  )

  def generatedSources = T {
    Seq(PathRef(shared.generateCoreSources(T.ctx().dest)))
  }
}

object main extends MillModule {
  def moduleDeps = Seq(core, clientserver)


  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )

  def generatedSources = T {
    Seq(PathRef(shared.generateCoreSources(T.ctx().dest)))
  }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0){
    def generatedSources = T {
      Seq(PathRef(shared.generateCoreTestSources(T.ctx().dest)))
    }
  }
}


object scalaworker extends MillModule{
  def moduleDeps = Seq(main, scalalib)

  def ivyDeps = Agg(
    ivy"org.scala-sbt::zinc:1.0.5"
  )
  def testArgs = Seq(
    "-DMILL_SCALA_WORKER=" + runClasspath().map(_.path).mkString(",")
  )
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

    scalaworker.testArgs() ++
    Seq("-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","))
  }
}


object scalajslib extends MillModule {

  def moduleDeps = Seq(scalalib)

  def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_BRIDGE_0_6" -> jsbridges("0.6").compile().classes.path,
      "MILL_SCALAJS_BRIDGE_1_0" -> jsbridges("1.0").compile().classes.path
    )
    scalaworker.testArgs() ++ (for((k, v) <- mapping.toSeq) yield s"-D$k=$v")
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
      shared.downloadTestRepo("lihaoyi/upickle", "7f33085c890db7550a226c349832eabc3cd18769", T.ctx().dest/"upickle")
  )
}

object integration extends MillModule{
  def moduleDeps = Seq(moduledefs, scalalib, scalajslib)
  def testArgs = T{
    scalajslib.testArgs() ++
    scalaworker.testArgs() ++
    (for((k, v) <- testRepos()) yield s"-D$k=$v")
  }
  def forkArgs = testArgs()
}

def launcherScript(jvmArgs: Seq[String],
                   classPath: Agg[String]) = {
  val jvmArgsStr = jvmArgs.mkString(" ")
  val classPathStr = classPath.mkString(":")
  s"""#!/usr/bin/env sh
     |
     |case "$$1" in
     |  -i | --interactive )
     |    shift;
     |    exec java $jvmArgsStr $$JAVA_OPTS -cp "$classPathStr" mill.Main "$$@"
     |    ;;
     |  *)
     |    exec java $jvmArgsStr $$JAVA_OPTS -cp "$classPathStr" mill.clientserver.Client "$$@"
     |    ;;
     |esac
     """.stripMargin
}

object dev extends MillModule{
  def moduleDeps = Seq(scalalib, scalajslib)
  def forkArgs = T{
    scalalib.testArgs() ++ scalajslib.testArgs() ++ scalaworker.testArgs()
  }
  def launcher = T{
    val outputPath = T.ctx().dest / "run"

    write(outputPath, prependShellScript())

    val perms = java.nio.file.Files.getPosixFilePermissions(outputPath.toNIO)
    perms.add(PosixFilePermission.GROUP_EXECUTE)
    perms.add(PosixFilePermission.OWNER_EXECUTE)
    perms.add(PosixFilePermission.OTHERS_EXECUTE)
    java.nio.file.Files.setPosixFilePermissions(outputPath.toNIO, perms)
    PathRef(outputPath)
  }

  def assembly = T{
    mv(super.assembly().path, T.ctx().dest / 'mill)
    PathRef(T.ctx().dest / 'mill)
  }

  def prependShellScript = launcherScript(forkArgs(), runClasspath().map(_.path.toString))

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
  mv(
    createAssembly(
      dev.runClasspath().map(_.path),
      prependShellScript = launcherScript(
        Seq("-DMILL_VERSION=" + publishVersion()._2),
        Agg("$0")
      )
    ).path,
    T.ctx().dest / 'mill
  )
  PathRef(T.ctx().dest / 'mill)
}

val isMasterCommit = {
  sys.env.get("TRAVIS_PULL_REQUEST") == Some("false") &&
  (sys.env.get("TRAVIS_BRANCH") == Some("master") || sys.env("TRAVIS_TAG") != "")
}

def gitHead = T.input{
  sys.env.get("TRAVIS_COMMIT").getOrElse(
    %%('git, "rev-parse", "head")(pwd).out.string.trim()
  )
}

def publishVersion = T.input{
  val tag =
    try Option(
      %%('git, 'describe, "--exact-match", "--tags", gitHead())(pwd).out.string.trim()
    )
    catch{case e => None}

  tag match{
    case Some(t) => (t, t)
    case None =>
      val latestTaggedVersion = %%('git, 'describe, "--abbrev=0", "--tags")(pwd).out.trim

      val commitsSinceLastTag =
        %%('git, "rev-list", gitHead(), "--count")(pwd).out.trim.toInt -
        %%('git, "rev-list", latestTaggedVersion, "--count")(pwd).out.trim.toInt

      ("unstable", s"$latestTaggedVersion-$commitsSinceLastTag-${gitHead().take(6)}")
  }
}

def uploadToGithub(authKey: String) = T.command{
  val (releaseTag, label) = publishVersion()

  if (releaseTag != "unstable"){
    scalaj.http.Http("https://api.github.com/repos/lihaoyi/mill/releases")
      .postData(
        upickle.json.write(
          Js.Obj(
            "tag_name" -> Js.Str(releaseTag),
            "name" -> Js.Str(releaseTag)
          )
        )
      )
      .header("Authorization", "token " + authKey)
      .asString
  }

  upload.apply(release().path, releaseTag, label, authKey)
}
