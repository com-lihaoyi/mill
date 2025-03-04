lazy val root = (project in file("."))
  .enablePlugins(AssemblyPlugin) // Enables sbt-assembly
  .settings(
    name := "spark-app",
    version := "0.1",
    scalaVersion := "2.12.19",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % "3.5.4",
      "org.apache.spark" %% "spark-sql" % "3.5.4",
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )