package mill.main

import scala.util.Try
import mill.define.Task
import mill.api.Logger
import mill.Input
import mill.define.{Discover, ExternalModule}
import os.SubprocessException

import scala.util.control.NonFatal

/**
 * Utilities to derive a useful version string from the Git commit history,
 * including the latest tag, the commit SHA for non-tagged commits, number
 * of commits since latest tag, and a `DIRTY` suffix for workspaces with
 * un-committed changes. Used via `VcsVersion.Module.vcsState.format()`
 *
 * Originally distributed under the Apache License, Version 2.0
 * as https://github.com/lefou/mill-vcs-version
 */

object VcsVersion extends ExternalModule with VcsVersion {
  case class Vcs(val name: String)
  def git = Vcs("git")

  implicit val jsonify: upickle.default.ReadWriter[Vcs] = upickle.default.macroRW

  case class State(
      currentRevision: String,
      lastTag: Option[String],
      commitsSinceLastTag: Int,
      dirtyHash: Option[String],
      vcs: Option[Vcs]
  ) {

    def format(
        noTagFallback: String = "0.0.0",
        countSep: String = "-",
        commitCountPad: Byte = 0,
        revSep: String = "-",
        revHashDigits: Int = 6,
        dirtySep: String = "-DIRTY",
        dirtyHashDigits: Int = 8,
        tagModifier: String => String = stripV,
        untaggedSuffix: String = ""
    ): String = {
      val versionPart = tagModifier(lastTag.getOrElse(noTagFallback))

      val isUntagged = lastTag.isEmpty || commitsSinceLastTag > 0

      val commitCountPart = if (isUntagged) {
        s"$countSep${
            if (commitCountPad > 0) {
              (10000000000000L + commitsSinceLastTag).toString().substring(14 - commitCountPad, 14)
            } else if (commitCountPad == 0) commitsSinceLastTag
            else ""
          }"
      } else ""

      val revisionPart = if (isUntagged) {
        s"$revSep${currentRevision.take(revHashDigits)}"
      } else ""

      val dirtyPart = dirtyHash match {
        case None => ""
        case Some(d) => dirtySep + d.take(dirtyHashDigits)
      }

      val snapshotSuffix = if (isUntagged) untaggedSuffix else ""

      s"$versionPart$commitCountPart$revisionPart$dirtyPart$snapshotSuffix"
    }

    /**
     * By default we strip the leading v if a user uses it.
     * Ex. v2.3.2 -> 2.3.2
     * @param tag the tag to process
     * @return either the stripped tag or the tag verbatim
     */
    def stripV(tag: String): String =
      tag match {
        case t if t.startsWith("v") && Try(t.substring(1, 2).toInt).isSuccess =>
          t.substring(1)
        case t => t
      }
  }

  object State {
    implicit val jsonify: upickle.default.ReadWriter[State] = upickle.default.macroRW
  }

  lazy val millDiscover = Discover[this.type]
}

trait VcsVersion extends mill.Module {

  def vcsBasePath: os.Path = moduleDir

  /**
   * Calc a publishable version based on git tags and dirty state.
   *
   * @return A tuple of (the latest tag, the calculated version string)
   */
  def vcsState: Input[VcsVersion.State] = Task.Input { calcVcsState(Task.log) }

  def calcVcsState(logger: Logger): VcsVersion.State = {
    val curHeadRaw =
      try {
        Option(os.proc("git", "rev-parse", "HEAD").call(
          cwd = vcsBasePath,
          stderr = os.Pipe
        ).out.trim())
      } catch {
        case e: SubprocessException =>
          logger.error(s"${vcsBasePath} is not a git repository.")
          None
      }

    curHeadRaw match {
      case None =>
        VcsVersion.State("no-vcs", None, 0, None, None)

      case curHead =>
        // we have a proper git repo

        val exactTag =
          try {
            curHead
              .map(curHead =>
                os.proc("git", "describe", "--exact-match", "--tags", "--always", curHead)
                  .call(cwd = vcsBasePath, stderr = os.Pipe)
                  .out
                  .text()
                  .trim
              )
              .filter(_.nonEmpty)
          } catch {
            case NonFatal(_) => None
          }

        val lastTag: Option[String] = exactTag.orElse {
          try {
            Option(
              os.proc("git", "describe", "--abbrev=0", "--tags")
                .call(stderr = os.Pipe)
                .out
                .text()
                .trim()
            )
              .filter(_.nonEmpty)
          } catch {
            case NonFatal(_) => None
          }
        }

        val commitsSinceLastTag =
          if (exactTag.isDefined) 0
          else {
            curHead
              .map { curHead =>
                os.proc(
                  "git",
                  "rev-list",
                  curHead,
                  lastTag match {
                    case Some(tag) => Seq("--not", tag)
                    case _ => Seq()
                  },
                  "--count"
                ).call(stderr = os.Pipe)
                  .out
                  .trim()
                  .toInt
              }
              .getOrElse(0)
          }

        val dirtyHashCode: Option[String] =
          Option(os.proc("git", "diff").call(stderr = os.Pipe).out.text().trim()).flatMap {
            case "" => None
            case s => Some(Integer.toHexString(s.hashCode))
          }

        new VcsVersion.State(
          currentRevision = curHead.getOrElse(""),
          lastTag = lastTag,
          commitsSinceLastTag = commitsSinceLastTag,
          dirtyHash = dirtyHashCode,
          vcs = Option(VcsVersion.git)
        )
    }
  }

}
