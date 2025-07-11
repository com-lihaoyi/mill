package mill.api.daemon

/**
 * Utilities for creating classloaders for running compiled Java/Scala code in
 * isolated classpaths.
 */
object ClassLoader {

  def withContextClassLoader[T](cl: java.lang.ClassLoader)(t: => T): T = {
    val thread = Thread.currentThread()
    val oldCl = thread.getContextClassLoader()
    try {
      thread.setContextClassLoader(cl)
      t
    } finally thread.setContextClassLoader(oldCl)
  }
  def java9OrAbove: Boolean = !System.getProperty("java.specification.version").startsWith("1.")
}
