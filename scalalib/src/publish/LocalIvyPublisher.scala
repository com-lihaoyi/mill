package mill.scalalib.publish

import mill.api.Ctx

class LocalIvyPublisher(localIvyRepo: os.Path) {

  def publish(
      jar: os.Path,
      sourcesJar: os.Path,
      docJar: os.Path,
      pom: os.Path,
      ivy: os.Path,
      artifact: Artifact,
      extras: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Unit = {

    ctx.log.info(s"Publishing ${artifact} to ivy repo ${localIvyRepo}")
    val releaseDir = localIvyRepo / artifact.group / artifact.id / artifact.version
    writeFiles(
      jar -> releaseDir / "jars" / s"${artifact.id}.jar",
      sourcesJar -> releaseDir / "srcs" / s"${artifact.id}-sources.jar",
      docJar -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar",
      pom -> releaseDir / "poms" / s"${artifact.id}.pom",
      ivy -> releaseDir / "ivys" / "ivy.xml"
    )
    writeFiles(extras.map { entry =>
      (entry.file.path, releaseDir / s"${entry.ivyType}s" / s"${artifact.id}${entry.classifierPart}.${entry.ext}")
    }: _*)
  }

  private def writeFiles(fromTo: (os.Path, os.Path)*): Unit = {
    fromTo.foreach {
      case (from, to) =>
        os.copy.over(from, to, createFolders = true)
    }
  }

}

object LocalIvyPublisher extends LocalIvyPublisher(os.home / ".ivy2" / "local")
