package mill.spotless

import upickle.default.*

/**
 * Configuration for running Spotless.
 */
case class SpotlessConfig(
    extensions: Seq[String],
    steps: Seq[FormatterStepConfig],
    formatter: FormatterConfig = FormatterConfig()
) derives Reader
