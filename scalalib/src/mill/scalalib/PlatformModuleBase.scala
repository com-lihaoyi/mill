package mill.scalalib

import mill._
import os.Path

trait PlatformModuleBase extends JavaModule {
  override def millSourcePath: Path = super.millSourcePath / os.up

  /**
   * The platform suffix of this [[PlatformModuleBase]]. Useful if you want to
   * further customize the source paths or artifact names.
   */
  def platformCrossSuffix: String = millModuleSegments
    .value
    .collect { case l: mill.define.Segment.Label => l.value }
    .last

  override def sources: T[Seq[PathRef]] = Task.Sources {
    super.sources().flatMap { source =>
      val platformPath =
        PathRef(source.path / _root_.os.up / s"${source.path.last}-${platformCrossSuffix}")
      Seq(source, platformPath)
    }
  }

  override def artifactNameParts: T[Seq[String]] = super.artifactNameParts().dropRight(1)
}
