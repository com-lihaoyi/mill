package mill.api


import java.lang.reflect.InvocationTargetException

/**
 * This exception is specifically handled in [[mill.runner.MillMain]] and [[mill.runner.MillServerMain]]. You can use it, if you need to exit Mill with a nice error message.
 *
 * @param msg The error message, to be displayed to the user.
 */
class MillException(msg: String) extends Exception(msg)

object MillException {

  def catchWrapException[T](t: => T): Result[T] = {
    try mill.api.Result.Success(t)
    catch { case e =>
        mill.api.Result.Failure(
          makeResultException(e, new java.lang.Exception()).toString
        )
    }
  }

  def unwrapException(t: Throwable) = t match {
    case e: ExceptionInInitializerError if e.getCause != null => e.getCause
    case e: InvocationTargetException if e.getCause != null => e.getCause
    case e: NoClassDefFoundError if e.getCause != null => e.getCause
    case e => e
  }

  def makeResultException(e: Throwable, base: java.lang.Exception): mill.api.ExecResult.Exception = {
    val outerStack = new mill.api.ExecResult.OuterStack(base.getStackTrace)
    mill.api.ExecResult.Exception(unwrapException(e), outerStack)
  }
}


class BuildScriptException(msg: String, script: Option[String])
  extends MillException(
    script.map(_ + ": ").getOrElse("") + "Build script contains errors:\n" + msg
  ) {
  def this(msg: String) = this(msg, None)
}
