package mill.scalalib.publish

import mill.api.Ctx

class LocalM2Publisher(m2Repo: os.Path) {

  /**
   * Publishes a module in the local Maven repository
   *
   * @param pom The POM of this module
   * @param artifact Coordinates of this module
   * @param publishInfos Files to publish in this module
   * @param ctx
   * @return
   */
  def publish(
      pom: os.Path,
      artifact: Artifact,
      publishInfos: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Seq[os.Path] = {

    val releaseDir = m2Repo / artifact.group.split("[.]") / artifact.id / artifact.version
    ctx.log.info(s"Publish ${artifact.id}-${artifact.version} to ${releaseDir}")

    val toCopy: Seq[(os.Path, os.Path)] =
      Seq(pom -> releaseDir / s"${artifact.id}-${artifact.version}.pom") ++
        publishInfos.map { e =>
          e.file.path -> releaseDir / s"${artifact.id}-${artifact.version}${e.classifierPart}.${e.ext}"
        }
    toCopy.map {
      case (from, to) =>
        os.copy.over(from, to, createFolders = true)
        to
    }
  }

}
