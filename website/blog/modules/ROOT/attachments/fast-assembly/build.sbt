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
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", _ @ _*) => MergeStrategy.discard
      case _ => MergeStrategy.first
    }
  )