package mill.scalalib
import mill._

/**
 * A [[ScalaModule]] intended for defining `.jvm`/`.js`/`.native` submodules
 * It supports additional source directories per platform, e.g. `src-jvm/` or
 * `src-js/` and can be used inside a [[CrossScalaModule.Base]], to get one
 * source folder per platform per version e.g. `src-2.12-jvm/`.
 *
 * Adjusts the [[millSourcePath]] and [[artifactNameParts]] to ignore the last
 * path segment, which is assumed to be the name of the platform the module is
 * built against and not something that should affect the filesystem path or
 * artifact name
 */
trait PlatformScalaModule extends ScalaModule {
  override def millSourcePath = super.millSourcePath / os.up

  /**
   * The platform suffix of this [[PlatformScalaModule]]. Useful if you want to
   * further customize the source paths or artifact names.
   */
  def platformScalaSuffix: String = millModuleSegments
    .value
    .collect { case l: mill.define.Segment.Label => l.value }
    .last

  override def sources = T.sources {
    super.sources().flatMap { source =>
      val platformPath =
        PathRef(source.path / _root_.os.up / s"${source.path.last}-${platformScalaSuffix}")
      Seq(source, platformPath)
    }
  }

  override def artifactNameParts = super.artifactNameParts().dropRight(1)
}
