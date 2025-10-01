lazy val myCrossProject = project
  .in(file("."))
  .settings(
    organization := "com.example",
    name := "my-cross-project",
    version := "0.1.0-SNAPSHOT",
    crossScalaVersions := Seq("2.12.18", "2.13.12", "3.3.1"),
    javacOptions := Seq("--release", "8"),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test
  )
