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
  )(implicit ctx: Ctx.Log): Unit = publishLocal(jar, sourcesJar, docJar, pom, ivy, artifact, extras)

  def publishLocal(
      jar: os.Path,
      sourcesJar: os.Path,
      docJar: os.Path,
      pom: os.Path,
      ivy: os.Path,
      artifact: Artifact,
      extras: Seq[PublishInfo]
  )(implicit ctx: Ctx.Log): Seq[os.Path] = {

    ctx.log.info(s"Publishing ${artifact} to ivy repo ${localIvyRepo}")
    val releaseDir = localIvyRepo / artifact.group / artifact.id / artifact.version

    val toCopy: Seq[(os.Path, os.Path)] = Seq(
      jar -> releaseDir / "jars" / s"${artifact.id}.jar",
      sourcesJar -> releaseDir / "srcs" / s"${artifact.id}-sources.jar",
      docJar -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar",
      pom -> releaseDir / "poms" / s"${artifact.id}.pom",
      ivy -> releaseDir / "ivys/ivy.xml"
    ) ++ extras.map { entry =>
      (
        entry.file.path,
        releaseDir / s"${entry.ivyType}s" / s"${artifact.id}${entry.classifierPart}.${entry.ext}"
      )
    }

    toCopy.map {
      case (from, to) =>
        os.copy.over(from, to, createFolders = true)
        to
    }
  }

}

object LocalIvyPublisher
    extends LocalIvyPublisher(
      sys.props.get("ivy.home")
        .map(os.Path(_))
        .getOrElse(sys.env.get("IVY_HOME")
                   .map(os.Path(_))
                   .getOrElse(os.home / ".ivy2") / "local"
    )
