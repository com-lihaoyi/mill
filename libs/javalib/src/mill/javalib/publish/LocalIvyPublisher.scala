package mill.javalib.publish

import mill.api.TaskCtx
import mill.util.FileSetContents

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
   * @return The files created or written to when publishing locally this module
   */
  def publishLocal(
      pom: os.Path,
      ivy: Either[String, os.Path],
      artifact: Artifact,
      publishInfos: Seq[PublishInfo]
  )(implicit ctx: TaskCtx.Log): Seq[os.Path] =
    publishLocal(
      artifact = artifact,
      contents = LocalIvyPublisher.createFileSetContents(pom, ivy.merge, artifact, publishInfos)
    )

  /**
   * Publishes a module locally
   *
   * @param artifact Coordinates of this module
   * @param contents Files to publish, create with [[LocalIvyPublisher.createFileSetContents]].
   * @return The files created or written to when publishing locally this module
   */
  def publishLocal(
      artifact: Artifact,
      contents: Map[os.SubPath, FileSetContents.Writable]
  )(implicit ctx: TaskCtx.Log): Seq[os.Path] = {
    ctx.log.info(s"Publishing ${artifact} to ivy repo ${localIvyRepo}")
    val releaseDir = localIvyRepo / artifact.group / artifact.id / artifact.version
    FileSetContents.writeTo(releaseDir, contents)
  }

}

object LocalIvyPublisher
    extends LocalIvyPublisher(
      sys.props.get("ivy.home")
        .map(os.Path(_))
        .getOrElse(os.home / ".ivy2") / "local"
    ) {

  /**
   * @param pom          The POM of this module
   * @param ivy          If right, the path to the ivy.xml file of this module; if left, its content as a String
   * @param artifact     Coordinates of this module
   * @param publishInfos Files to publish in this module
   */
  def createFileSetContents(
      pom: os.Path,
      ivy: FileSetContents.Writable,
      artifact: Artifact,
      publishInfos: Seq[PublishInfo]
  ): Map[os.SubPath, FileSetContents.Writable] = {
    Map(
      os.SubPath("poms") / s"${artifact.id}.pom" -> pom,
      os.SubPath("ivys") / "ivy.xml" -> ivy
    ) ++ publishInfos.iterator.map { entry =>
      os.SubPath(s"${entry.ivyType}s") / s"${artifact.id}${entry.classifierPart}.${entry.ext}" ->
        entry.file.path
    }
  }
}
