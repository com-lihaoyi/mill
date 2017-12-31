import sbt._

object Dependencies {
  val scalatest   = "org.scalatest"         %% "scalatest"      % "3.0.4"   % Test

  // Used in Akka file watcher
  val akka        = "com.typesafe.akka"     %% "akka-actor"     % "2.5.6"

  // For shapeless based Reader/Scanner
  val shapeless   = "com.chuusai"           %% "shapeless"      % "2.3.2"

  // Used in Benchmarks only
  val commonsio   = "commons-io"             % "commons-io"     % "2.5"
  val fastjavaio  = "fastjavaio"             % "fastjavaio"     % "1.0"   from "https://github.com/williamfiset/FastJavaIO/releases/download/v1.0/fastjavaio.jar"
}
