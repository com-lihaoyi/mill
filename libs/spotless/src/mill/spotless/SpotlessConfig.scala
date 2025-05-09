package mill.spotless

import upickle.default.*

import java.nio.file.PathMatcher

case class SpotlessConfig(
    formatters: Seq[FormatterConfig],
    excludes: Seq[PathMatcher] = Seq()
) derives Reader {
  require(formatters.nonEmpty, "formatters must be non-empty")
}
