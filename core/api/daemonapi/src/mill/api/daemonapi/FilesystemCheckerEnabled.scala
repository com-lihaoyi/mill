package mill.api.daemonapi

/**
 * Variable indicating whether or not the user has globally disabled
 * the OS-Lib filesystem checks.
 */
object FilesystemCheckerEnabled extends scala.util.DynamicVariable(true)
