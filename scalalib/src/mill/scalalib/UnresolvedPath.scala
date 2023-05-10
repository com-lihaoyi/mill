package mill.scalalib

import mill.define.{Segment, Segments}
import mill.eval.{EvaluatorPaths, EvaluatorPathsResolver}
import os.Path
import upickle.default.{ReadWriter, macroRW}

/**
 * An unresolved path is relative to some unspecified destination
 * which depends on the actual configuration at evaluation time.
 * Hence, you need to call [[#resolve]] with an instance of
 * [[mill.eval.EvaluatorPathsResolver]] to get the final [[os.Path]].
 */
sealed trait UnresolvedPath {
  def resolve(pathResolver: EvaluatorPathsResolver): Path
}
object UnresolvedPath {
  case class ResolvedPath private (path: String) extends UnresolvedPath {
    override def resolve(pathResolver: EvaluatorPathsResolver): Path = os.Path(path)
  }
  object ResolvedPath {
    def apply(path: os.Path): ResolvedPath = ResolvedPath(path.toString)

    implicit def upickleRW: ReadWriter[ResolvedPath] = macroRW
  }

  case class DestPath private (
      subPath: String,
      segments: Seq[String],
      foreignSegments: Option[Seq[String]] = None
  ) extends UnresolvedPath {
    override def resolve(pathResolver: EvaluatorPathsResolver): Path = {
      pathResolver.resolveDest(
        Segments(segments.map(Segment.Label(_))),
        foreignSegments.map(o => Segments(o.map(Segment.Label(_))))
      ).dest / os.SubPath(subPath)
    }
  }
  object DestPath {
    def apply(
        subPath: os.SubPath,
        segments: Segments,
        foreignSegments: Option[Segments]
    ): DestPath = {
      DestPath(
        subPath.toString(),
        EvaluatorPaths.makeSegmentStrings(segments),
        foreignSegments.map(EvaluatorPaths.makeSegmentStrings(_))
      )
    }

    implicit def upickleRW: ReadWriter[DestPath] = macroRW
  }

  implicit def upickleRW: ReadWriter[UnresolvedPath] =
    ReadWriter.merge(ResolvedPath.upickleRW, DestPath.upickleRW)
}
