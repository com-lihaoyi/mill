package mill.javalib.publish

import mill.api.TaskCtx
import mill.util.{FileSetContents, Jvm}

/**
 * Logic to publish modules to your `~/.m2` repository
 */
class LocalM2Publisher(m2Repo: os.Path) {

  /**
   * Publishes a module in the local Maven repository
   *
   * @param pom The POM of this module
   * @param artifact Coordinates of this module
   * @param publishInfos Files to publish in this module
   * @return
   */
  def publish(
      pom: os.Path,
      artifact: Artifact,
      publishInfos: Seq[PublishInfo]
  )(using ctx: TaskCtx.Log): Seq[os.Path] =
    publish(artifact, LocalM2Publisher.createFileSetContents(pom, artifact, publishInfos))

  /**
   * Publishes a module in the local Maven repository
   *
   * @param artifact Coordinates of this module
   * @param contents Files to publish, create with [[LocalM2Publisher.createFileSetContents]].
   */
  def publish(
      artifact: Artifact,
      contents: Map[os.SubPath, FileSetContents.Writable]
  )(using ctx: TaskCtx.Log): Seq[os.Path] = {

    val releaseDir = m2Repo / artifact.group.split("[.]") / artifact.id / artifact.version
    // `Jvm.realAbs`: example tests assert on `/home/.../.m2/...` in the log; the alias form
    // would log `../mill-home/.m2/...` and fail the assertion.
    val releaseDirDisplay = Jvm.realAbs(releaseDir)
    ctx.log.info(
      s"Publish ${artifact.id}-${artifact.version} to $releaseDirDisplay. " +
        s"File list: [${contents.keys.toVector.sorted.mkString(", ")}]"
    )
    FileSetContents.writeTo(m2Repo, contents)
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
  ): Map[os.SubPath, os.Path] = {
    val releaseDir =
      (os.RelPath(".") / artifact.group.split("[.]") / artifact.id / artifact.version).asSubPath
    val artifactStr = s"${artifact.id}-${artifact.version}"

    Map(releaseDir / s"$artifactStr.pom" -> pom) ++
      publishInfos.iterator.map { e =>
        releaseDir / s"$artifactStr${e.classifierPart}.${e.ext}" -> e.file.path
      }.toMap
  }
}
