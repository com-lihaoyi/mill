
lazy val root = project
  .in(file("."))
  .settings(
    name := "Scala 3 Project Template",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.6.3",
    scalacOptions := List("-unchecked", "-feature"),
    javacOptions := List("-source", "17", "-target", "17"),
    libraryDependencies ++= List(
      "com.lihaoyi" %% "upickle" % "4.1.0",
      "com.lihaoyi" %% "os-lib" % "0.11.4"
    ),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    resolvers += "jitpack" at "https://jitpack.io",
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/ghuser/myproject"),
        "scm:git:git@github.com:ghuser/myproject.git"
      )
    ),
    developers := List(
      Developer(
        "ghuser",
        "Gh User",
        "ghuser@example.com",
        url("https://example.com")
      )
    )
  )
