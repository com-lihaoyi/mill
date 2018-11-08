package mill.scalalib.publish


object LocalPublisher {

  private val root: os.Path = os.home / ".ivy2" / "local"

  def publish(jar: os.Path,
              sourcesJar: os.Path,
              docJar: os.Path,
              pom: os.Path,
              ivy: os.Path,
              artifact: Artifact): Unit = {
    val releaseDir = root / artifact.group / artifact.id / artifact.version
    writeFiles(
      jar -> releaseDir / "jars" / s"${artifact.id}.jar",
      sourcesJar -> releaseDir / "srcs" / s"${artifact.id}-sources.jar",
      docJar -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar",
      pom -> releaseDir / "poms" / s"${artifact.id}.pom",
      ivy -> releaseDir / "ivys" / "ivy.xml"
    )
  }

  private def writeFiles(fromTo: (os.Path, os.Path)*): Unit = {
    fromTo.foreach {
      case (from, to) =>
        os.makeDir.all(to / os.up)
        os.copy.over(from, to)
    }
  }

}
