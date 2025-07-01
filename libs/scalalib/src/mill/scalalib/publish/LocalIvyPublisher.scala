package mill.scalalib.publish

import mill.define.TaskCtx
import mill.scalalib.FileSetContents

class LocalIvyPublisher(localIvyRepo: os.Path) {

  /**
   * Publishes a module locally
   *
   * @param artifact Coordinates of this module
   * @param contents Files to publish, create with [[LocalIvyPublisher.createFileSetContents]].
   * @return The files created or written to when publishing locally this module
   */
  def publishLocal(
      artifact: Artifact,
      contents: FileSetContents.Any,
  )(implicit ctx: TaskCtx.Log): Seq[os.Path] = {
    ctx.log.info(s"Publishing ${artifact} to ivy repo ${localIvyRepo}")
    val releaseDir = localIvyRepo / artifact.group / artifact.id / artifact.version
    contents.writeTo(releaseDir)
  }
}

object LocalIvyPublisher
    extends LocalIvyPublisher(
      sys.props.get("ivy.home")
        .map(os.Path(_))
        .getOrElse(os.home / ".ivy2") / "local"
    ) {

  /**
   * @param pom The POM of this module
   * @param ivy If right, the path to the ivy.xml file of this module; if left, its content as a String
   * @param artifact Coordinates of this module
   * @param publishInfos Files to publish in this module
   */
  def createFileSetContents(
    pom: os.Path,
    ivy: FileSetContents.Contents,
    artifact: Artifact,
    publishInfos: Seq[PublishInfo]
  ): FileSetContents.Any = {
    FileSetContents(Map(
      os.RelPath("poms") / s"${artifact.id}.pom" -> FileSetContents.Contents.Path(pom),
      os.RelPath("ivys") / "ivy.xml" -> ivy
    ) ++ publishInfos.iterator.map { entry =>
      os.RelPath(s"${entry.ivyType}s") / s"${artifact.id}${entry.classifierPart}.${entry.ext}" ->
        FileSetContents.Contents.Path(entry.file.path)
    })
  }
}
