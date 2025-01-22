package mill.scalalib.publish

import mill.api.Ctx

class LocalIvyPublisher(localIvyRepo: os.Path) {

  @deprecated("Use publishLocal instead", "Mill 0.11.7")
  def publish(
      jar: os.Path,
      sourcesJar: os.Path,
      docJar: os.Path,
      pom: os.Path,
      ivy: os.Path,
      artifact: Artifact,
      extras: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Unit =
    publishLocal(Some(jar), Some(sourcesJar), Some(docJar), pom, Right(ivy), artifact, extras)

  /**
   * Publishes a module locally
   *
   * @param jar The JAR of this module, if it has one
   * @param sourcesJar The source JAR of this module, if it has one
   * @param docJar The javadoc JAR of this module, if it has one
   * @param pom The POM of this module
   * @param ivy If right, the path to the ivy.xml file of this module; if left, its content as a String
   * @param artifact Coordinates of this module
   * @param extras Extra files to publish in this module
   * @param ctx
   * @return The files created or written to when publishing locally this module
   */
  def publishLocal(
      jar: Option[os.Path],
      sourcesJar: Option[os.Path],
      docJar: Option[os.Path],
      pom: os.Path,
      ivy: Either[String, os.Path],
      artifact: Artifact,
      extras: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Seq[os.Path] = {

    ctx.log.info(s"Publishing ${artifact} to ivy repo ${localIvyRepo}")
    val releaseDir = localIvyRepo / artifact.group / artifact.id / artifact.version

    val toCopy: Seq[(Either[String, os.Path], os.Path)] =
      jar.map(Right(_) -> releaseDir / "jars" / s"${artifact.id}.jar").toSeq ++
        sourcesJar.map(Right(_) -> releaseDir / "srcs" / s"${artifact.id}-sources.jar").toSeq ++
        docJar.map(Right(_) -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar").toSeq ++
        Seq(
          Right(pom) -> releaseDir / "poms" / s"${artifact.id}.pom",
          ivy -> releaseDir / "ivys/ivy.xml"
        ) ++
        extras.map { entry =>
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

  // bin-compat shim
  def publishLocal(
      jar: os.Path,
      sourcesJar: os.Path,
      docJar: os.Path,
      pom: os.Path,
      ivy: os.Path,
      artifact: Artifact,
      extras: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Seq[os.Path] =
    publishLocal(
      Some(jar),
      Some(sourcesJar),
      Some(docJar),
      pom,
      Right(ivy),
      artifact,
      extras
    )
}

object LocalIvyPublisher
    extends LocalIvyPublisher(
      sys.props.get("ivy.home")
        .map(os.Path(_))
        .getOrElse(os.home / ".ivy2") / "local"
    )
