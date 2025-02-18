
val scala212 = "2.12.18"
val scala213 = "2.13.16"
val scala3 = "3.3.5"

ThisBuild / scalaVersion := scala212
ThisBuild / organization := "com.lihaoyi"
ThisBuild / homepage := Some(url("https://mill-build.org/"))
ThisBuild / licenses := List("MIT" -> url("https://spdx.org/licenses/MIT.html"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/com-lihaoyi/mill"),
    "scm:git:git@github.com:com-lihaoyi/mill.git"
  )
)
ThisBuild / developers := List(
  Developer("lihaoyi", "Li Haoyi", "", url("https://github.com/lihaoyi")),
  Developer("lefou", "Tobias Roeser", "", url("https://github.com/lefou"))
)

lazy val core = (project in file("core"))
  .settings(
    name := "sbt-build-extract-core",
    description := "Core models for sbt-build-extract",
    crossScalaVersions := List(scala212, scala213, scala3),
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.1.0"
    )
  )

lazy val plugin = (project in file("plugin"))
  .settings(
    name := "sbt-build-extract",
    description := "Sbt plugin for extracting build information",
    sbtPlugin := true
  )
  .dependsOn(core)
