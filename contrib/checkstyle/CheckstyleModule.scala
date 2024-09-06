package mill.contrib.checkstyle

/**
 * Integrate Checkstyle into a [[JavaModule]].
 *
 * See https://checkstyle.sourceforge.io/
 */
trait CheckstyleModule extends JavaModule {
  /** The `checkstyle` version to use. Defaults to [[BuildInfo.checkstyleVersion]]. */
  def checkstyleVersion: T[String] = T.input {
    BuildInfo.checkstyleVersion
  }

  /**
   * The dependencies of the `checkstyle` compiler plugin.
   */
  def checkstyleDeps: T[Agg[Dep]] = T {
    Agg(
      ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}"
    )
  }

  /**
   * The classpath of the `checkstyle` compiler plugin.
   */
  def checkstyleClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task { checkstyleDeps().map(bindDependency()) })()
  }

}
