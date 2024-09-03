package mill.api

/**
 * This exception is specifically handled in [[mill.runner.MillMain]] and [[mill.runner.MillServerMain]]. You can use it, if you need to exit Mill with a nice error message.
 * @param msg The error message, to be displayed to the user.
 */
class MillException protected[api] (msg: String, cause: Throwable = null)
    extends Exception(msg, cause) {
  def this(msg: String) = this(msg, null)
}

class BuildScriptException(msg: String, script: Option[String])
    extends MillException(
      script.map(_ + ": ").getOrElse("") + "Build script contains errors:\n" + msg
    ) {
  def this(msg: String) = this(msg, None)
}

/**
 * This exception is meant to specifically exit a task evaluation with a [[Result.Failure]].
 * The actual catch and lift logic need to be applied in the evaluator.
 * @param msg The error message, to be displayed to the user.
 * @param cause A potential cause or `null`
 */
class ResultFailureException(msg: String, cause: Throwable = null)
    extends MillException(msg, cause)
//    {
//  def toFailure[T]: Result.Failure[T] = Result.Failure(msg)
//}
