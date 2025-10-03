package mill.javalib

import mill.api.daemon.internal.{UnresolvedPathApi, internal}
import mill.api.{ExecutionPaths, Segment, Segments, Task}
import upickle.{ReadWriter, macroRW}

/**
 * An unresolved path is relative to some unspecified destination
 * which depends on the actual configuration at evaluation time.
 *
 * Hence, you need to call [[resolve]] with the Mill's 'out/' path (for example from `EvaluatorApi.outPathJava` to
 * get the final [[os.Path]].
 */
sealed trait UnresolvedPath extends UnresolvedPathApi[os.Path] {
  def resolve(outPath: os.Path): os.Path
}
object UnresolvedPath {

  /** Resolves paths relative to the `out` folder. */
  @internal
  private[mill] def resolveRelativeToOut(
      task: Task.Named[?],
      mkPath: os.SubPath => os.SubPath = identity
  ): UnresolvedPath.DestPath =
    UnresolvedPath.DestPath(mkPath(os.sub), task.ctx.segments)

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
