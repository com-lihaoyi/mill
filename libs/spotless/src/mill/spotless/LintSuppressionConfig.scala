package mill.spotless

import com.diffplug.spotless.LintSuppression
import upickle.default.*

/**
 * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/LintSuppression.java LintSuppression]].
 */
case class LintSuppressionConfig(
    path: Option[String] = None,
    step: Option[String] = None,
    shortCode: Option[String] = None
) derives Reader {
  def build: LintSuppression =
    val ls = LintSuppression()
    path.foreach(ls.setPath)
    step.foreach(ls.setStep)
    shortCode.foreach(ls.setShortCode)
    ls
}
