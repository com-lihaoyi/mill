package mill.javalib

import mill.api.daemon.internal.UnresolvedPathApi
import mill.api.{BuildCtx, ExecutionPaths, Segment, Segments}
import upickle.{ReadWriter, macroRW}

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
    override def resolve(outPath: os.Path): os.Path = {
      val workspaceAlias = "out/mill-workspace"
      val homeAlias = "out/mill-home"
      def resolveFromAlias(base: os.Path, aliasIdx: Int, alias: String): os.Path = {
        val suffix = path.substring(aliasIdx + alias.length).stripPrefix("/")
        if (suffix.isEmpty) base else base / os.RelPath(suffix)
      }

      if (path == workspaceAlias) BuildCtx.workspaceRoot
      else if (path.startsWith(workspaceAlias + "/"))
        BuildCtx.workspaceRoot / os.RelPath(path.stripPrefix(workspaceAlias + "/"))
      else if (path == homeAlias) os.home
      else if (path.startsWith(homeAlias + "/"))
        os.home / os.RelPath(path.stripPrefix(homeAlias + "/"))
      else {
        val workspaceIdx = path.indexOf(workspaceAlias)
        if (workspaceIdx >= 0)
          resolveFromAlias(BuildCtx.workspaceRoot, workspaceIdx, workspaceAlias)
        else {
          val homeIdx = path.indexOf(homeAlias)
          if (homeIdx >= 0) resolveFromAlias(os.home, homeIdx, homeAlias)
          else os.Path(path, os.pwd)
        }
      }
    }
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
