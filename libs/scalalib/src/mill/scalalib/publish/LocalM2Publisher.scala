package mill.scalalib.publish

import mill.define.TaskCtx
import mill.scalalib.FileSetContents
import os.{RelPath, SubPath}

class LocalM2Publisher(m2Repo: os.Path) {

  /**
   * Publishes a module in the local Maven repository
   *
   * @param artifact Coordinates of this module
   * @param contents Files to publish, create with [[LocalM2Publisher.createFileSetContents]].
   * @return
   */
  def publish(
      artifact: Artifact,
      contents: FileSetContents.Any
  )(implicit ctx: TaskCtx.Log): Seq[os.Path] = {
    val releaseDir = m2Repo / artifact.group.split("[.]") / artifact.id / artifact.version
    ctx.log.info(s"Publish ${artifact.id}-${artifact.version} to ${releaseDir}")
    contents.writeTo(m2Repo)
  }

}
object LocalM2Publisher {
  /**
   * @param pom The POM of this module
   * @param artifact Coordinates of this module
   * @param publishInfos Files to publish in this module
   */
  def createFileSetContents(
    pom: os.Path,
    artifact: Artifact,
    publishInfos: Seq[PublishInfo]
  ): FileSetContents.Path = {
    val dir = (RelPath(".") / artifact.group.split("[.]") / artifact.id / artifact.version).asSubPath

    pomFileSetContents(pom, artifact) ++ publishInfos.iterator.map { e =>
      dir / s"${artifact.id}-${artifact.version}${e.classifierPart}.${e.ext}" ->
        FileSetContents.Contents.Path(e.file.path)
    }.toMap
  }

  def pomFileSetContents(pom: os.Path, artifact: Artifact): FileSetContents.Path = {
    FileSetContents(Map(SubPath(s"${artifact.id}-${artifact.version}.pom") -> FileSetContents.Contents.Path(pom)))
  }
}
