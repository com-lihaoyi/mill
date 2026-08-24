package mill.javalib.testrunner

import mill.api.daemon.internal.internal

/**
 * Whether selectors (e.g. from `testOnly`) match a candidate test class: [[globMatch]] says
 * whether it should run at all, [[isExplicitlySpecified]] says whether it was named exactly
 * rather than matched by a glob.
 *
 * `rawSelectors` defaults to `matchSelectors`. Pass it separately only when `matchSelectors`
 * has already been resolved to concrete class names (e.g. the batch scheduler's per-group selector)
 */
@internal final class ClassFilter private (matchSelectors: Seq[String], rawSelectors: Option[Seq[String]]) {
  private val matchers = matchSelectors.map(TestRunnerUtils.matchesGlob)
  private val exactMatchers =
    rawSelectors.getOrElse(matchSelectors).filterNot(_.contains('*')).map(TestRunnerUtils.matchesGlob)

  def hasFilters: Boolean = matchSelectors.nonEmpty

  def globMatch(className: String): Boolean = {
    val name = className.stripSuffix("$")
    matchers.isEmpty || matchers.exists(f => f(name))
  }

  def isExplicitlySpecified(className: String): Boolean = {
    val name = className.stripSuffix("$")
    exactMatchers.exists(f => f(name))
  }
}

@internal object ClassFilter {
  def apply(matchSelectors: Seq[String], rawSelectors: Option[Seq[String]] = None): ClassFilter =
    new ClassFilter(matchSelectors, rawSelectors)
}
