package mill.scalalib.publish

import mill.api.Ctx

class LocalM2Publisher(m2Repo: os.Path) {

  def publish(
    jar: os.Path,
    sourcesJar: os.Path,
    docJar: os.Path,
    pom: os.Path,
    artifact: Artifact,
    extras: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Seq[os.Path] = {

    val releaseDir = m2Repo / artifact.group.split("[.]") / artifact.id / artifact.version
    ctx.log.info(s"Publish ${artifact.id}-${artifact.version} to ${releaseDir}")
    os.makeDir.all(releaseDir)
    val toCopy: Seq[(os.Path, os.Path)] = Seq(
      jar -> releaseDir / s"${artifact.id}-${artifact.version}.jar",
      sourcesJar -> releaseDir / s"${artifact.id}-${artifact.version}-sources.jar",
      docJar -> releaseDir / s"${artifact.id}-${artifact.version}-javadoc.jar",
      pom -> releaseDir / s"${artifact.id}-${artifact.version}.pom"
    ) ++ extras.map { e =>
      e.file.path -> releaseDir / s"${artifact.id}-${artifact.version}${e.classifierPart}.${e.ext}"
    }
    toCopy.map {
        case (from, to) =>
          os.copy.over(from, to)
          to
      }
  }

}
