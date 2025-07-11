package mill.javalib.publish

import mill.api.TaskCtx

/**
 * Logic to publish modules to your `~/.ivy2/local` repository
 */
class LocalIvyPublisher(localIvyRepo: os.Path) {

  /**
   * Publishes a module locally
   *
   * @param pom The POM of this module
   * @param ivy If right, the path to the ivy.xml file of this module; if left, its content as a String
   * @param artifact Coordinates of this module
   * @param publishInfos Files to publish in this module
   * @param ctx
   * @return The files created or written to when publishing locally this module
   */
  def publishLocal(
      pom: os.Path,
      ivy: Either[String, os.Path],
      artifact: Artifact,
      publishInfos: Seq[PublishInfo]
  )(implicit ctx: TaskCtx.Log): Seq[os.Path] = {

    ctx.log.info(s"Publishing ${artifact} to ivy repo ${localIvyRepo}")
    val releaseDir = localIvyRepo / artifact.group / artifact.id / artifact.version

    val toCopy: Seq[(Either[String, os.Path], os.Path)] =
      Seq(
        Right(pom) -> releaseDir / "poms" / s"${artifact.id}.pom",
        ivy -> releaseDir / "ivys/ivy.xml"
      ) ++
        publishInfos.map { entry =>
          (
            Right(entry.file.path),
            releaseDir / s"${entry.ivyType}s" / s"${artifact.id}${entry.classifierPart}.${entry.ext}"
          )
        }

    toCopy.map {
      case (from, to) =>
        from match {
          case Left(content) => os.write.over(to, content, createFolders = true)
          case Right(path) => os.copy.over(path, to, createFolders = true)
        }
        to
    }
  }

}

object LocalIvyPublisher
    extends LocalIvyPublisher(
      sys.props.get("ivy.home")
        .map(os.Path(_))
        .getOrElse(os.home / ".ivy2") / "local"
    )
