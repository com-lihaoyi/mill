
name := """sbt-mill-init-generate-project-tree"""
organization := "com.lihaoyi"
version := "0.1-SNAPSHOT"

sbtPlugin := true

console / initialCommands := """import mill.main.sbt._"""
