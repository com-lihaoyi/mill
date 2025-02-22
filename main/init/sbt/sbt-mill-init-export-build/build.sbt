name := """sbt-mill-init-export-build"""
organization := "com.lihaoyi"
version := "SNAPSHOT"

sbtPlugin := true

console / initialCommands := """import mill.main.sbt._"""

libraryDependencies += "com.lihaoyi" %% "mill-main-init-sbt-models" % version.value
