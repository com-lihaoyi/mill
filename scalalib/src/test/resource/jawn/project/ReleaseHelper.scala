import sbt._
import sbt.Keys._
import sbt.complete.Parser

object ReleaseHelper {

  /** Invoke a command and carry out remaining commands until completion.
    *
    * This is necessary because sbt-release's releaseStepCommand does not
    * execute remaining commands, which sbt-doge relies on.
    *
    * Based on https://github.com/playframework/playframework/blob/master/framework/project/Release.scala
    *
    * NOTE: This can be removed in favor of https://github.com/sbt/sbt-release/pull/171 if/when merged upstream
    */
  def runCommandAndRemaining(command: String): State => State = { originalState =>
    val originalRemaining = originalState.remainingCommands

    @annotation.tailrec
    def runCommand(command: String, state: State): State = {
      val newState = Parser.parse(command, state.combinedParser) match {
        case Right(cmd) => cmd()
        case Left(msg) => throw sys.error(s"Invalid programmatic input:\n$msg")
      }
      if (newState.remainingCommands.isEmpty) {
        newState
      } else {
        runCommand(newState.remainingCommands.head, newState.copy(remainingCommands = newState.remainingCommands.tail))
      }
    }

    runCommand(command, originalState.copy(remainingCommands = Nil)).copy(remainingCommands = originalRemaining)
  }
}
