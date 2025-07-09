package mill.javalib

import mill.api.daemon.internal.UnresolvedPathApi
import mill.api.{ExecutionPaths, Segment, Segments}
import upickle.default.{ReadWriter, macroRW}

/**
 * An unresolved path is relative to some unspecified destination
 * which depends on the actual configuration at evaluation time.
 * Hence, you need to call [[#resolve]] with an instance of
 * [[ExecutionPathsResolver]] to get the final [[os.Path]].
 */
sealed trait UnresolvedPath extends UnresolvedPathApi[os.Path] {
  def resolve(outPath: os.Path): os.Path
}
object UnresolvedPath {
  case class ResolvedPath private (path: String) extends UnresolvedPath {
    override def resolve(outPath: os.Path): os.Path = os.Path(path)
  }
  object ResolvedPath {
    def apply(path: os.Path): ResolvedPath = ResolvedPath(path.toString)

    implicit def upickleRW: ReadWriter[ResolvedPath] = macroRW
  }

  /** A path relative to the `out` folder, in form of `out/${segments}.dest/$subPath`. */
  case class DestPath private (
      subPath: String,
      segments: Seq[String]
  ) extends UnresolvedPath {
    override def resolve(outPath: os.Path): os.Path = {
      ExecutionPaths.resolve(
        outPath,
        Segments(segments.map(Segment.Label(_)))
      ).dest / os.SubPath(subPath)
    }
  }
  object DestPath {
    def apply(
        subPath: os.SubPath,
        segments: Segments
    ): DestPath = {
      DestPath(subPath.toString(), segments.parts)
    }

    implicit def upickleRW: ReadWriter[DestPath] = macroRW
  }

  implicit def upickleRW: ReadWriter[UnresolvedPath] =
    ReadWriter.merge(ResolvedPath.upickleRW, DestPath.upickleRW)
}
