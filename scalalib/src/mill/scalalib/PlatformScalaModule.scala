package mill.scalalib

import mill.{PathRef, T, Task}

/**
 * A [[ScalaModule]] intended for defining `.jvm`/`.js`/`.native` submodules
 * It supports additional source directories per platform, e.g. `src-jvm/` or
 * `src-js/` and can be used inside a [[CrossScalaModule.Base]], to get one
 * source folder per platform per version e.g. `src-2.12-jvm/`.
 *
 * Adjusts the [[moduleDir]] and [[artifactNameParts]] to ignore the last
 * path segment, which is assumed to be the name of the platform the module is
 * built against and not something that should affect the filesystem path or
 * artifact name
 */
trait PlatformScalaModule extends /*PlatformModuleBase with*/ ScalaModule{
  override def moduleDir: os.Path = super.moduleDir / os.up
  /**
   * The platform suffix of this [[PlatformModuleBase]]. Useful if you want to
   * further customize the source paths or artifact names.
   */
  def platformCrossSuffix: String = moduleSegments
    .value
    .collect { case l: mill.define.Segment.Label => l.value }
    .last

  override def sourcesFolders: Seq[os.SubPath] = super.sourcesFolders.flatMap {
    source => Seq(source, source / os.up / s"${source.last}-${platformCrossSuffix}")
  }

  override def artifactNameParts: T[Seq[String]] = super.artifactNameParts().dropRight(1)
}
