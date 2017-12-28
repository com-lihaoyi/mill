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

val coreSettings = Seq(
  sourceGenerators in Compile += Def.task {
    import sys.process._
    val dir = (sourceManaged in Compile).value
    if (!dir.exists()) {
      IO.createDirectory(dir)
      Seq("amm", "shared.sc", "generateSources", dir.toString).!
    }
    IO.listFiles(dir).toSeq
  }.taskValue,

  sourceGenerators in Test += Def.task {
    import sys.process._
    val dir = (sourceManaged in Test).value
    if (!dir.exists()) {
      IO.createDirectory(dir)
      Seq("amm", "shared.sc", "generateTests", dir.toString).!
    }
    IO.listFiles(dir).toSeq
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
      val dir = (sourceManaged in Compile).value
      if (!dir.exists()) {
        IO.createDirectory(dir)
        Seq("amm", "shared.sc", "downloadBridgeSource", dir.toString, bridgeVersion).!
      }
      (dir ** "*.scala").get
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
    fork := true,
    connectInput in (Test, run) := true,
    outputStrategy in (Test, run) := Some(StdoutOutput),
    mainClass in (Test, run) := Some("mill.Main"),
    baseDirectory in (Test, run) := (baseDirectory in (Compile, run)).value / "..",
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
