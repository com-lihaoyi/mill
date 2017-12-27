import ammonite.ops._
import coursier.maven.MavenRepository
import mill._
import mill.scalaplugin._, publish._
import mill.modules.Jvm.createAssembly

trait MillPublishModule extends PublishModule {
  def publishWithFullScalaVersion = true

  def pomSettings = PomSettings(
    organization = "com.lihaoyi",
    description = publishName(),
    developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi/mill")),
    licenses = Seq(License("MIT License", "https://spdx.org/licenses/MIT.html#licenseText")),
    scm = SCM("https://github.com/lihaoyi/mill", "scm:git:https://github.com/lihaoyi/mill.git"),
    url = "https://github.com/lihaoyi/mill"
  )
}

object CompilerPlugin extends SbtScalaModule{
  def scalaVersion = "2.12.4"
  def basePath = pwd / 'plugin
  def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion()),
    Dep("com.lihaoyi", "sourcecode", "0.1.4")
  )
}

trait MillModule extends SbtScalaModule{ outer =>
  def scalaVersion = "2.12.4"

  def compileIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))

  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  def testArgs = T{ Seq.empty[String] }

  object test extends this.Tests{
    def defaultCommandName() = "forkTest"
    def forkArgs = T{ testArgs() }
    def projectDeps =
      if (this == Core.test) Seq(Core)
      else Seq(outer, Core.test)
    def ivyDeps = Seq(Dep("com.lihaoyi", "utest", "0.6.0"))
    def testFramework = "mill.UTestFramework"
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(CompilerPlugin.jar())

  }
}

object Core extends MillModule {
  def projectDeps = Seq(CompilerPlugin)

  def compileIvyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
  )

  def ivyDeps = Seq(
    Dep("com.lihaoyi", "sourcecode", "0.1.4"),
    Dep("com.lihaoyi", "pprint", "0.5.3"),
    Dep.Point("com.lihaoyi", "ammonite", "1.0.3-20-75e58ac"),
    Dep("com.typesafe.play", "play-json", "2.6.6"),
    Dep("org.scala-sbt", "zinc", "1.0.5"),
    Dep.Java("org.scala-sbt", "test-interface", "1.0")
  )

  def basePath = pwd / 'core

  object CodeGenerator {
    private def generateLetters(n: Int) = {
      val base = 'A'.toInt
      (0 until n).map(i => (i + base).toChar)
    }

    private def write(dir: String, filename: String, s: String) = {
      import java.io.{BufferedWriter, FileWriter}

      val path = java.nio.file.Paths.get(dir, filename)
      val w = new BufferedWriter(new FileWriter(path.toFile))
      w.write(s)
      w.close()
    }

    def generateApplyer(dir: String) = {
      def generate(n: Int) = {
        val uppercases = generateLetters(n)
        val lowercases = uppercases.map(Character.toLowerCase)
        val typeArgs   = uppercases.mkString(", ")
        val zipArgs    = lowercases.mkString(", ")
        val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")

        val body   = s"mapCtx(zip($zipArgs)) { case (($zipArgs), z) => cb($zipArgs, z) }"
        val zipmap = s"def zipMap[$typeArgs, Res]($parameters)(cb: ($typeArgs, Ctx) => Z[Res]) = $body"
        val zip    = s"def zip[$typeArgs]($parameters): TT[($typeArgs)]"

        if (n < 22) List(zipmap, zip).mkString(System.lineSeparator) else zip
      }
      val output = List(
          "package mill.define",
          "import scala.language.higherKinds",
          "trait ApplyerGenerated[TT[_], Z[_], Ctx] {",
          "def mapCtx[A, B](a: TT[A])(f: (A, Ctx) => Z[B]): TT[B]",
          (2 to 22).map(generate).mkString(System.lineSeparator),
          "}").mkString(System.lineSeparator)

      write(dir, "ApplicativeGenerated.scala", output)
    }

    def generateTarget(dir: String) = {
      def generate(n: Int) = {
        val uppercases = generateLetters(n)
        val lowercases = uppercases.map(Character.toLowerCase)
        val typeArgs   = uppercases.mkString(", ")
        val args       = lowercases.mkString(", ")
        val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")
        val body       = uppercases.zipWithIndex.map { case (t, i) => s"args[$t]($i)" }.mkString(", ")

        s"def zip[$typeArgs]($parameters) = makeT[($typeArgs)](Seq($args), (args: Ctx) => ($body))"
      }

      val output = List(
        "package mill.define",
        "import scala.language.higherKinds",
        "import mill.eval.Result",
        "import mill.util.Ctx",
        "trait TargetGenerated {",
        "type TT[+X]",
        "def makeT[X](inputs: Seq[TT[_]], evaluate: Ctx => Result[X]): TT[X]",
        (3 to 22).map(generate).mkString(System.lineSeparator),
        "}").mkString(System.lineSeparator)
      write(dir, "TaskGenerated.scala", output)
    }

    def generateApplicativeTest(dir: String) = {
      def generate(n: Int): String = {
          val uppercases = generateLetters(n)
          val lowercases = uppercases.map(Character.toLowerCase)
          val typeArgs   = uppercases.mkString(", ")
          val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: Option[$upper]" }.mkString(", ")
          val result = lowercases.mkString(", ")
          val forArgs = lowercases.map(i => s"$i <- $i").mkString("; ")
          s"def zip[$typeArgs]($parameters) = { for ($forArgs) yield ($result) }"
      }

      val output = List(
          "package mill.define",
          "trait OptGenerated {",
          (2 to 22).map(generate).mkString(System.lineSeparator),
          "}"
      ).mkString(System.lineSeparator)

      write(dir, "ApplicativeTestsGenerated.scala", output)
    }

    def generateSources(p: Path) = {
      val dir = p.toString()
      generateApplyer(dir)
      generateTarget(dir)
    }

    def generateTests(p: Path) = {
      generateApplicativeTest(p.toString())
    }
  }

  def sources = {
    CodeGenerator.generateSources(this.basePath / 'src / 'main / 'scala / 'mill / 'define)
    super.sources
  }

  object tests extends super.Tests {
    override def testFramework = Core.test.testFramework
    override def sources = {
      CodeGenerator.generateTests(pwd / 'core / 'src / 'test / 'scala / 'mill / 'define)
      super.sources
    }
  }

  val cross =
    for(jarLabel <- mill.define.Cross("jarA", "jarB", "jarC"))
    yield new mill.Module{
      def printIt() = T.command{
        println("PRINTING IT: " + jarLabel)
      }
      def jar = T{
        val dest = T.ctx().dest
        ammonite.ops.mkdir(dest/ammonite.ops.up)
        ammonite.ops.cp(Core.jar().path, dest)

        PathRef(dest)
      }
    }
}

val bridgeVersions = Seq("2.10.6", "2.11.8", "2.11.11", "2.12.3", "2.12.4")

val bridges = for(crossVersion <- mill.define.Cross(bridgeVersions:_*)) yield new MillPublishModule {
  def publishName = "mill-bridge"
  def publishVersion = "0.1"

  def basePath = pwd / 'bridge
  def scalaVersion = crossVersion
  def sources = T.source {
    val path = basePath / 'src
    mkdir(path)
    path
  }
  def allSources = T{

    val v = crossVersion.split('.').dropRight(1).mkString(".")
    val url =
      s"http://repo1.maven.org/maven2/org/scala-sbt/compiler-bridge_$v/1.0.5/compiler-bridge_$v-1.0.5-sources.jar"
    val curlDest = T.ctx().dest
    implicit val pwd = curlDest
    mkdir(curlDest)
    rm(curlDest/"bridge.jar")

    %("curl", "-L", "-o", curlDest / "bridge.jar", url)
    %("unzip", curlDest / "bridge.jar" , "-d", curlDest / 'classes)


    Seq(PathRef(curlDest / 'classes))
  }
  def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", crossVersion),
    Dep.Java("org.scala-sbt", "compiler-interface", "1.0.5")
  )
}

object ScalaPlugin extends MillModule {
  def projectDeps = Seq(Core)
  def basePath = pwd / 'scalaplugin

  def bridgeCompiles = mill.define.Task.traverse(bridges.items)(_._2.compile)
  def testArgs = T{
    val bridgeVersions = bridges.items.map(_._1.head.toString)

    for((version, compile) <- bridgeVersions.zip(bridgeCompiles()))
    yield {
      val underscored = version.replace('.', '_')
      val key = s"MILL_COMPILER_BRIDGE_$underscored"
      val value = compile.classes.path
      s"-D$key=$value"
    }
  }
}

val assemblyProjects = Seq(ScalaPlugin)

def assemblyClasspath = mill.define.Task.traverse(assemblyProjects)(_.assemblyClasspath)

def publishBridges(credentials: String, gpgPassphrase: String) = T.command {
  mill.define.Task.traverse(bridges.items)(_._2.publish(credentials, gpgPassphrase))
}

def assemblyBase(classpath: Seq[Path], extraArgs: String)
                (implicit ctx: mill.util.Ctx.DestCtx) = {
  createAssembly(
    classpath,
    prependShellScript =
      "#!/usr/bin/env sh\n" +
      s"""exec java $extraArgs $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
  )
}

def devAssembly = T{
  assemblyBase(assemblyClasspath().flatten, ScalaPlugin.testArgs().mkString(" "))
}

def releaseAssembly = T{
  assemblyBase(assemblyClasspath().flatten, "")
}

def idea() = T.command{ mill.scalaplugin.GenIdea() }