scalaVersion := "2.12.4"

name := "forge"

organization := "com.lihaoyi"

libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test"

testFrameworks += new TestFramework("forge.Framework")

parallelExecution in Test := false

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
  "com.lihaoyi" %% "sourcecode" % "0.1.4",
  "com.lihaoyi" %% "pprint" % "0.5.3",
  "com.lihaoyi" % "ammonite" % "1.0.3" cross CrossVersion.full,
  "com.typesafe.play" %% "play-json" % "2.6.6",
  "org.scala-sbt" %% "zinc" % "1.0.3"
)

test in assembly := {}

assemblyOption in assembly := (assemblyOption in assembly).value.copy(
  prependShellScript = Some(
    // G1 Garbage Collector is awesome https://github.com/lihaoyi/Ammonite/issues/216
    Seq("#!/usr/bin/env sh", """exec java -jar -Xmx500m -XX:+UseG1GC $JAVA_OPTS "$0" "$@"""")
  )
)