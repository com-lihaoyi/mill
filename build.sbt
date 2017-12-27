val sharedSettings = Seq(
  scalaVersion := "2.12.4",
  organization := "com.lihaoyi",
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test",

  testFrameworks += new TestFramework("mill.UTestFramework"),

  parallelExecution in Test := false,
  test in assembly := {},

  libraryDependencies += "com.lihaoyi" %% "acyclic" % "0.1.7" % "provided",
  resolvers += Resolver.sonatypeRepo("releases"),
  scalacOptions += "-P:acyclic:force",
  autoCompilerPlugins := true,
  addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7"),
  libraryDependencies += "com.lihaoyi" % "ammonite" % "1.0.3-20-75e58ac" % "test" cross CrossVersion.full,

  sourceGenerators in Test += Def.task {
    val file = (sourceManaged in Test).value / "amm.scala"
    IO.write(file, """object amm extends App { ammonite.Main().run() }""")
    Seq(file)
  }.taskValue
)

val coreSettings = Seq(
  sourceGenerators in Compile += Def.task {
    object CodeGenerator {
      private def generateLetters(n: Int) = {
        val base = 'A'.toInt
        (0 until n).map(i => (i + base).toChar)
      }

      def generateApplyer(dir: File) = {
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

        val file = dir / "ApplicativeGenerated.scala"
        IO.write(file, output)
        file
      }

      def generateTarget(dir: File) = {
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

        val file = dir / "TaskGenerated.scala"
        IO.write(file, output)
        file
      }

      def generateSources(dir: File) = {
        Seq(generateApplyer(dir), generateTarget(dir))
      }
    }
    val dir = (sourceManaged in Compile).value
    CodeGenerator.generateSources(dir)
  }.taskValue,

  sourceGenerators in Test += Def.task {
    object CodeGenerator {
      private def generateLetters(n: Int) = {
        val base = 'A'.toInt
        (0 until n).map(i => (i + base).toChar)
      }

      def generateApplicativeTest(dir: File) = {
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

        val file = dir / "ApplicativeTestsGenerated.scala"
        IO.write(file, output)
        file
      }

      def generateTests(dir: File) = {
        Seq(generateApplicativeTest(dir))
      }
    }

    val dir = (sourceManaged in Test).value
    CodeGenerator.generateTests(dir)
  }.taskValue
)

val pluginSettings = Seq(
  scalacOptions in Test ++= {
    val jarFile = (packageBin in (plugin, Compile)).value
    val addPlugin = "-Xplugin:" + jarFile.getAbsolutePath
    // add plugin timestamp to compiler options to trigger recompile of
    // main after editing the plugin. (Otherwise a 'clean' is needed.)
    val dummy = "-Jdummy=" + jarFile.lastModified
    Seq(addPlugin, dummy)
  }
)

def bridge(bridgeVersion: String) = Project(
  id = "bridge" + bridgeVersion.replace('.', '_'),
  base = file("bridge/" + bridgeVersion.replace('.', '_')),
  settings = Seq(
    organization := "com.lihaoyi",
    scalaVersion := bridgeVersion,
    name := "mill-bridge",
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-sbt" % "compiler-interface" % "1.0.5"
    ),
    (sourceGenerators in Compile) += Def.task{
      import sys.process._
      import collection.JavaConverters._
      val v = scalaBinaryVersion.value
      val url =
        s"http://repo1.maven.org/maven2/org/scala-sbt/compiler-bridge_$v/1.0.5/compiler-bridge_$v-1.0.5-sources.jar"
      val curlDest = java.nio.file.Paths.get(target.value.toString, "sources")

      if (!java.nio.file.Files.exists(curlDest)) {
        Seq("rm", "-rf", curlDest.toString).!
        java.nio.file.Files.createDirectories(curlDest)

        Seq("curl", "-L", "-o", curlDest.resolve("bridge.jar").toString, url).!
        Seq("unzip", curlDest.resolve("bridge.jar").toString, "-d", curlDest.toString).!
      }
      val sources = java.nio.file.Files.walk(curlDest)
        .iterator
        .asScala
        .filter(_.toString.endsWith(".scala"))
        .map(_.toFile)
        .toSeq

      sources
    }.taskValue
  )
)
lazy val bridge2_10_6 = bridge("2.10.6")
lazy val bridge2_11_8 = bridge("2.11.8")
//lazy val bridge2_11_9 = bridge("2.11.9")
//lazy val bridge2_11_10 = bridge("2.11.10")
lazy val bridge2_11_11 = bridge("2.11.11")
//lazy val bridge2_12_0 = bridge("2.12.0")
//lazy val bridge2_12_1 = bridge("2.12.1")
//lazy val bridge2_12_2 = bridge("2.12.2")
lazy val bridge2_12_3 = bridge("2.12.3")
lazy val bridge2_12_4 = bridge("2.12.4")

lazy val core = project
  .dependsOn(plugin)
  .settings(
    sharedSettings,
    coreSettings,
    pluginSettings,
    name := "mill-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "com.lihaoyi" %% "sourcecode" % "0.1.4",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.lihaoyi" % "ammonite" % "1.0.3-20-75e58ac" cross CrossVersion.full,
      "org.scala-sbt" %% "zinc" % "1.0.5",
      "org.scala-sbt" % "test-interface" % "1.0"
    )
  )

lazy val plugin = project
  .settings(
    sharedSettings,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "com.lihaoyi" %% "sourcecode" % "0.1.4"
    ),
    publishArtifact in Compile := false
  )

val bridgeProps = Def.task{
  val mapping = Map(
    "MILL_COMPILER_BRIDGE_2_10_6" -> (packageBin in (bridge2_10_6, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_11_8" -> (packageBin in (bridge2_11_8, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_11_11" -> (packageBin in (bridge2_11_11, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_12_3" -> (packageBin in (bridge2_12_3, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_12_4" -> (packageBin in (bridge2_12_4, Compile)).value.absolutePath
  )
  for((k, v) <- mapping) yield s"-D$k=$v"
}

lazy val scalaplugin = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    sharedSettings,
    pluginSettings,
    name := "mill-scalaplugin",
    fork := true,
    baseDirectory in Test := (baseDirectory in Test).value / "..",
    javaOptions := bridgeProps.value.toSeq
  )

lazy val bin = project
  .dependsOn(scalaplugin)
  .settings(
    sharedSettings,
    mainClass in (Compile, run) := Some("mill.Main"),
    assemblyOption in assembly := {
      (assemblyOption in assembly).value.copy(
        prependShellScript = Some(
          Seq(
            "#!/usr/bin/env sh",
            s"""exec java ${bridgeProps.value.mkString(" ")} -cp "$$0" mill.Main "$$@" """
          )
        )
      )
    },
    assembly in Test := {
      val dest = target.value/"mill"
      IO.copyFile(assembly.value, dest)
      import sys.process._
      Seq("chmod", "+x", dest.getAbsolutePath).!
      dest
    }
  )
