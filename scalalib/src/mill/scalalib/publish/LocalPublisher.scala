package mill.scalalib.publish

import ammonite.ops._

object LocalPublisher {

  private val root: Path = home / ".ivy2" / "local"

  def publish(jar: Path,
              sourcesJar: Path,
              docsJar: Path,
              pom: Path,
              ivy: Path,
              artifact: Artifact): Unit = {
    val releaseDir = root / artifact.group / artifact.id / artifact.version
    writeFiles(
      jar -> releaseDir / "jars" / s"${artifact.id}.jar",
      sourcesJar -> releaseDir / "srcs" / s"${artifact.id}-sources.jar",
      docsJar -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar",
      pom -> releaseDir / "poms" / s"${artifact.id}.pom",
      ivy -> releaseDir / "ivys" / "ivy.xml"
    )
  }

  private def writeFiles(fromTo: (Path, Path)*): Unit = {
    fromTo.foreach {
      case (from, to) =>
        mkdir(to / up)
        cp.over(from, to)
    }
  }

}
