package mill.kotlinlib

import mill._
import os.Path

/**
 * A [[KotlinModule]] intended for defining `.jvm`/`.js`/etc. submodules
 * It supports additional source directories per platform, e.g. `src-jvm/` or
 * `src-js/`.
 *
 * Adjusts the [[millSourcePath]] and [[artifactNameParts]] to ignore the last
 * path segment, which is assumed to be the name of the platform the module is
 * built against and not something that should affect the filesystem path or
 * artifact name
 */
trait PlatformKotlinModule extends KotlinModule {
  override def millSourcePath: Path = super.millSourcePath / os.up

  /**
   * The platform suffix of this [[PlatformKotlinModule]]. Useful if you want to
   * further customize the source paths or artifact names.
   */
  def platformKotlinSuffix: String = millModuleSegments
    .value
    .collect { case l: mill.define.Segment.Label => l.value }
    .last

  override def sources: T[Seq[PathRef]] = Task.Sources {
    super.sources().flatMap { source =>
      val platformPath =
        PathRef(source.path / _root_.os.up / s"${source.path.last}-$platformKotlinSuffix")
      Seq(source, platformPath)
    }
  }

  override def artifactNameParts: T[Seq[String]] = super.artifactNameParts().dropRight(1)
}
