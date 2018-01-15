import java.io.File

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
  addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7")
)


val pluginSettings = Seq(
  scalacOptions in Test ++= {
    val jarFile = (packageBin in (moduledefs, Compile)).value
    val addPlugin = "-Xplugin:" + jarFile.getAbsolutePath
    // add plugin timestamp to compiler options to trigger recompile of
    // main after editing the plugin. (Otherwise a 'clean' is needed.)
    val dummy = "-Jdummy=" + jarFile.lastModified
    Seq(addPlugin, dummy)
  }
)

lazy val ammoniteRunner = project
  .in(file("target/ammoniteRunner"))
  .settings(
    scalaVersion := "2.12.4",
    target := baseDirectory.value,
    libraryDependencies +=
      "com.lihaoyi" % "ammonite" % "1.0.3-21-05b5d32" cross CrossVersion.full
  )


def ammoniteRun(hole: SettingKey[File], args: String => List[String], suffix: String = "") = Def.task{
  val target = hole.value / suffix
  if (!target.exists()) {
    IO.createDirectory(target)
    (runner in(ammoniteRunner, Compile)).value.run(
      "ammonite.Main",
      (dependencyClasspath in(ammoniteRunner, Compile)).value.files,
      args(target.toString),
      streams.value.log
    )
  }
  target
}


def bridge(bridgeVersion: String) = Project(
  id = "bridge" + bridgeVersion.replace('.', '_'),
  base = file("target/bridge/" + bridgeVersion.replace('.', '_')),
  settings = Seq(
    organization := "com.lihaoyi",
    scalaVersion := bridgeVersion,
    name := "mill-bridge",
    target := baseDirectory.value,
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-sbt" % "compiler-interface" % "1.0.5"
    ),
    (sourceGenerators in Compile) += ammoniteRun(
      sourceManaged in Compile,
      List("shared.sc", "downloadBridgeSource", _, bridgeVersion)
    ).taskValue.map(x => (x ** "*.scala").get)
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
  .dependsOn(moduledefs)
  .settings(
    sharedSettings,
    pluginSettings,
    name := "mill-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "com.lihaoyi" %% "sourcecode" % "0.1.4",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.lihaoyi" % "ammonite" % "1.0.3-21-05b5d32" cross CrossVersion.full,
      "org.scala-sbt" %% "zinc" % "1.0.5",
      "org.scala-sbt" % "test-interface" % "1.0"
    ),
    sourceGenerators in Compile += {
      ammoniteRun(sourceManaged in Compile, List("shared.sc", "generateCoreSources", _))
        .taskValue
        .map(x => (x ** "*.scala").get)
    },

    sourceGenerators in Test += {
      ammoniteRun(sourceManaged in Test, List("shared.sc", "generateCoreTestSources", _))
        .taskValue
        .map(x => (x ** "*.scala").get)
    }
  )

lazy val moduledefs = project
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

lazy val scalalib = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    sharedSettings,
    pluginSettings,
    name := "mill-scalalib",
    fork := true,
    baseDirectory in Test := (baseDirectory in Test).value / "..",
    javaOptions := bridgeProps.value.toSeq
  )
lazy val scalajslib = project
  .dependsOn(scalalib % "compile->compile;test->test")
  .settings(
    sharedSettings,
    name := "mill-scalajslib",
    fork in Test := true,
    baseDirectory in Test := (baseDirectory in Test).value / "..",
    javaOptions in Test := jsbridgeProps.value.toSeq
  )
def jsbridge(binary: String, version: String) =
  Project(
    id = "scalajsbridge_" + binary.replace('.', '_'),
    base = file("scalajslib/jsbridges/" + binary)
  )
  .settings(
    organization := "com.lihaoyi",
    scalaVersion := "2.12.4",
    name := "mill-js-bridge",
    libraryDependencies ++= Seq("org.scala-js" %% "scalajs-tools" % version)
  )
lazy val scalajsbridge_0_6 = jsbridge("0.6", "0.6.21")
lazy val scalajsbridge_1_0 = jsbridge("1.0", "1.0.0-M2")
val jsbridgeProps = Def.task{
  def bridgeClasspath(depClasspath: Classpath, jar: File) = {
    (depClasspath.files :+ jar).map(_.absolutePath).mkString(File.pathSeparator)
  }
  val mapping = Map(
    "MILL_SCALAJS_BRIDGE_0_6" -> bridgeClasspath(
      (dependencyClasspath in (scalajsbridge_0_6, Compile)).value,
      (packageBin in (scalajsbridge_0_6, Compile)).value
    ),
    "MILL_SCALAJS_BRIDGE_1_0" -> bridgeClasspath(
      (dependencyClasspath in (scalajsbridge_1_0, Compile)).value,
      (packageBin in (scalajsbridge_1_0, Compile)).value
    )
  )
  for((k, v) <- mapping) yield s"-D$k=$v"
}

val testRepos = Map(
  "MILL_ACYCLIC_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", _),
    suffix = "acyclic"
  ),
  "MILL_JAWN_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", _),
    suffix = "jawn"
  ),
  "MILL_BETTERFILES_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "pathikrit/better-files", "e235722f91f78b8f34a41b8332d7fae3e8a64141", _),
    suffix = "better-files"
  )
)

lazy val integration = project
  .dependsOn(core % "compile->compile;test->test", scalalib, scalajslib)
  .settings(
    sharedSettings,
    name := "integration",
    fork := true,
    baseDirectory in Test := (baseDirectory in Test).value / "..",
    javaOptions in Test := {
      val kvs = Seq(
        "MILL_ACYCLIC_REPO" -> testRepos("MILL_ACYCLIC_REPO").value,
        "MILL_JAWN_REPO" -> testRepos("MILL_JAWN_REPO").value,
        "MILL_BETTERFILES_REPO" -> testRepos("MILL_BETTERFILES_REPO").value
      )
      for((k, v) <- kvs) yield s"-D$k=$v"
    }
  )

lazy val bin = project
  .in(file("target/bin"))
  .dependsOn(scalalib, scalajslib)
  .settings(
    sharedSettings,
    target := baseDirectory.value,
    fork := true,
    connectInput in (Test, run) := true,
    outputStrategy in (Test, run) := Some(StdoutOutput),
    mainClass in (Test, run) := Some("mill.Main"),
    baseDirectory in (Test, run) := (baseDirectory in (Compile, run)).value / ".." / "..",
    assemblyOption in assembly := {
      (assemblyOption in assembly).value.copy(
        prependShellScript = Some(
          Seq(
            "#!/usr/bin/env sh",
            s"""exec java ${(bridgeProps.value ++ jsbridgeProps.value).mkString(" ")} -cp "$$0" mill.Main "$$@" """
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
