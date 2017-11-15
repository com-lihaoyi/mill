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
      Seq("#!/usr/bin/env sh", """exec java -jar -Xmx500m -XX:+UseG1GC $JAVA_OPTS "$0" "$@"""")
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

lazy val core = project
  .settings(
    sharedSettings,
    name := "mill-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "com.lihaoyi" %% "sourcecode" % "0.1.4",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.lihaoyi" % "ammonite" % "1.0.3" cross CrossVersion.full,
      "org.scala-sbt" %% "zinc" % "1.0.3",
      "org.scala-sbt" % "test-interface" % "1.0"
    )
  )

lazy val scalaplugin = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    sharedSettings,
    name := "mill-scalaplugin"
  )