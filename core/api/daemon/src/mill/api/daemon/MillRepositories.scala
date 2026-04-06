package mill.api.daemon

import scala.util.DynamicVariable

/**
 * Global repositories configured via `mill-repositories` in the build file header or
 * `.mill-repositories` config file. These are used for resolving Mill's own dependencies
 * (daemon jars, JVM index) and as default repositories for `CoursierModule`.
 */
object MillRepositories {
  private[mill] val current: DynamicVariable[Seq[String]] = DynamicVariable(Seq.empty)

  def get: Seq[String] = current.value

  private[mill] def withValue[T](repos: Seq[String])(block: => T): T =
    current.withValue(repos)(block)
}
