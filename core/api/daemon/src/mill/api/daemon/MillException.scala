package mill.api.daemon

/**
 * This exception is specifically handled in [[mill.runner.MillMain]] and [[mill.runner.MillDaemonMain]]. You can use it, if you need to exit Mill with a nice error message.
 * @param msg The error message, to be displayed to the user.
 */
class MillException(msg: String) extends Exception(msg)
