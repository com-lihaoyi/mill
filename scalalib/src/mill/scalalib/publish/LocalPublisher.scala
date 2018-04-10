package mill.scalalib.publish

import ammonite.ops._

object LocalPublisher {

  private val root: Path = home / ".ivy2" / "local"

  def publish(jar: Path,
              sourcesJar: Option[Path],
              docJar: Option[Path],
              pom: Path,
              ivy: Path,
              artifact: Artifact): Unit = {
    val releaseDir = root / artifact.group / artifact.id / artifact.version

    val toWrite: List[Option[(Path, Path)]] = List(
      Option(jar -> releaseDir / "jars" / s"${artifact.id}.jar")
    , sourcesJar.map(_ -> releaseDir / "srcs" / s"${artifact.id}-sources.jar")
    , docJar.map(_ -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar")
    , Option(pom -> releaseDir / "poms" / s"${artifact.id}.pom")
    , Option(ivy -> releaseDir / "ivys" / "ivy.xml")
    )
    writeFiles(toWrite.flatten: _*)
  }

  private def writeFiles(fromTo: (Path, Path)*): Unit = {
    fromTo.foreach {
      case (from, to) =>
        mkdir(to / up)
        cp.over(from, to)
    }
  }

}
