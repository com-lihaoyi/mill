ThisBuild / organization := "com.example"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.15"

lazy val core = (project in file("core"))
  .dependsOn(util)

lazy val util = (project in file("util"))
