package mill.internal

import mill.api.daemon.Logger
import mill.api.daemon.internal.LauncherLocking.WaitReporter

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires lock-wait status into the multi-line prompt. Two flavours:
 *
 *   - When the supplied logger already has an active prompt-line (e.g. a
 *     per-task `PrefixLogger` set up via [[Logger.withPromptLine]]), wait
 *     status is rendered as the *detail suffix* of that existing line so
 *     the user sees "task-name | blocked on ..." attached to the
 *     row that's already in their multi-line prompt.
 *   - When there's no preexisting prompt-line for this logger's key (e.g.
 *     the launcher session logger during meta-build / exclusive-lock
 *     acquisition, where no batch-row has been opened yet), each
 *     `reportWait` opens a fresh synthetic prompt-line for the duration of
 *     the wait and removes it on close. Without this, `setPromptDetail`
 *     would write a detail for a key that the prompt isn't displaying, and
 *     nothing would appear.
 *
 * If the prompt isn't live (`enableTicker == false`, BSP, `--ticker false`,
 * no-daemon early bootstrap), degrades to a one-shot stderr line so
 * non-interactive runs still see the wait.
 */
object PromptWaitReporter {

  /**
   * Counter for synthetic-line keys so concurrent waits in one session
   * don't collide on the same prompt row.
   */
  private val syntheticCounter = new AtomicLong()

  def fromLogger(logger: Logger, fallback: PrintStream): WaitReporter =
    if (!logger.prompt.enableTicker) WaitReporter.stderr(fallback)
    else {
      val prompt = logger.prompt
      val baseKey = logger.logKey
      (msg: String) => {
        if (baseKey.nonEmpty) {
          prompt.setPromptDetail(baseKey, msg)
          () => prompt.setPromptDetail(baseKey, "")
        } else {
          // Session logger has no prompt-line — synthesise one or the
          // wait detail has nothing to attach to.
          val syntheticKey = Seq("wait-" + syntheticCounter.incrementAndGet())
          prompt.setPromptLine(syntheticKey, "", msg)
          () => prompt.removePromptLine(syntheticKey, msg)
        }
      }
    }
}
