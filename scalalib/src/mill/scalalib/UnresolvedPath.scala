package mill.scalalib

import mill.define.{Segment, Segments, ExecutionPaths}
import os.Path
import upickle.default.{ReadWriter, macroRW}

/**
 * An unresolved path is relative to some unspecified destination
 * which depends on the actual configuration at evaluation time.
 * Hence, you need to call [[#resolve]] with an instance of
 * [[ExecutionPathsResolver]] to get the final [[os.Path]].
 */
sealed trait UnresolvedPath {
  def resolve(outPath: os.Path): Path
}
object UnresolvedPath {
  case class ResolvedPath private (path: String) extends UnresolvedPath {
    override def resolve(outPath: os.Path): Path = os.Path(path)
  }
  object ResolvedPath {
    def apply(path: os.Path): ResolvedPath = ResolvedPath(path.toString)

    implicit def upickleRW: ReadWriter[ResolvedPath] = macroRW
  }

  case class DestPath private (
      subPath: String,
      segments: Seq[String]
  ) extends UnresolvedPath {
    override def resolve(outPath: os.Path): Path = {
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
