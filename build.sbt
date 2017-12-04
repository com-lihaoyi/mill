val sharedSettings = Seq(
  scalaVersion := "2.12.4",
  organization := "com.lihaoyi",
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test",

  testFrameworks += new TestFramework("mill.UTestFramework"),

  parallelExecution in Test := false,
  test in assembly := {},

  assemblyOption in assembly := (assemblyOption in assembly).value.copy(
    prependShellScript = Some(
      // G1 Garbage Collector is awesome https://github.com/lihaoyi/Ammonite/issues/216
      Seq("#!/usr/bin/env sh", """exec java -cp "$0" mill.Main "$@" """)
    )
  ),
  assembly in Test := {
    val dest = target.value/"mill"
    IO.copyFile(assembly.value, dest)
    import sys.process._
    Seq("chmod", "+x", dest.getAbsolutePath).!
    dest
  },
  libraryDependencies += "com.lihaoyi" %% "acyclic" % "0.1.7" % "provided",
  scalacOptions += "-P:acyclic:force",
  autoCompilerPlugins := true,
  addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7"),
  libraryDependencies += "com.lihaoyi" % "ammonite" % "1.0.3" % "test" cross CrossVersion.full,

  sourceGenerators in Test += Def.task {
    val file = (sourceManaged in Test).value / "amm.scala"
    IO.write(file, """object amm extends App { ammonite.Main().run() }""")
    Seq(file)
  }.taskValue

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
      if (java.nio.file.Files.exists(curlDest)) {
        java.nio.file.Files.walk(curlDest)
          .iterator()
          .asScala
          .toSeq
          .reverse
          .foreach(java.nio.file.Files.delete)
      }

      java.nio.file.Files.createDirectories(curlDest)

      Seq("curl", "-L", "-o", curlDest.resolve("bridge.jar").toString, url).!
      Seq("unzip", curlDest.resolve("bridge.jar").toString, "-d", curlDest.toString).!

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
  .settings(
    sharedSettings,
    name := "mill-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "com.lihaoyi" %% "sourcecode" % "0.1.4",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.lihaoyi" % "ammonite" % "1.0.3" cross CrossVersion.full,
      "org.scala-sbt" %% "zinc" % "1.0.5",
      "org.scala-sbt" % "test-interface" % "1.0"
    )
  )

lazy val scalaplugin = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    sharedSettings,
    name := "mill-scalaplugin",
    (compile in Test) := {
      val a = (packageBin in (bridge2_10_6, Compile)).value
      val b = (packageBin in (bridge2_11_8, Compile)).value
//      val c = (packageBin in (bridge2_11_9, Compile)).value
//      val d = (packageBin in (bridge2_11_10, Compile)).value
      val e = (packageBin in (bridge2_11_11, Compile)).value
//      val f = (packageBin in (bridge2_12_0, Compile)).value
//      val g = (packageBin in (bridge2_12_1, Compile)).value
//      val h = (packageBin in (bridge2_12_2, Compile)).value
      val i = (packageBin in (bridge2_12_3, Compile)).value
      val j = (packageBin in (bridge2_12_4, Compile)).value
      (compile in Test).value
    }
  )
