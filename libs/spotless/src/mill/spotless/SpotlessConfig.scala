package mill.spotless

import upickle.default.*
import upickle.implicits.flatten

/**
 * Configuration for running Spotless.
 */
case class SpotlessConfig(
    extensions: Seq[String],
    steps: Seq[FormatterStepConfig],
    @flatten formatter: FormatterConfig
) derives Reader
