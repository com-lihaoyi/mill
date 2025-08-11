package mill.javalib

import mill._
import os.Path

/**
 * Shared logic between `PlatformScalaModule` an `PlatformJavaModule`
 */
trait PlatformModuleBase extends JavaModule {
  override def moduleDir: Path = super.moduleDir / os.up

  /**
   * The platform suffix of this [[PlatformModuleBase]]. Useful if you want to
   * further customize the source paths or artifact names.
   */
  def platformCrossSuffix: String = moduleSegments
    .value
    .collect { case l: mill.api.Segment.Label => l.value }
    .last

  override def sourcesFolders: Seq[os.SubPath] = super.sourcesFolders.flatMap {
    source => Seq(source, source / os.up / s"${source.last}-${platformCrossSuffix}")
  }

  override def artifactNameParts: T[Seq[String]] = super.artifactNameParts().dropRight(1)
}
