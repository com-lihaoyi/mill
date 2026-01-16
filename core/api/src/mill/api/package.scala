package mill

/**
 * Core language-agnostic Mill APIs for use in your build files to define
 * [[Task]]s, [[Module]]s, etc.
 */
package object api {

  /**
   * Exception thrown to signal a deferred exit request (e.g., from shutdown command).
   * Extends Error so it's not caught by NonFatal, but is explicitly handled
   * in the task evaluation framework to convert it to a success result.
   */
  class DeferredExitException(val reason: String, val exitCode: Int)
      extends Error(s"Deferred exit: $reason (exitCode=$exitCode)")
}
