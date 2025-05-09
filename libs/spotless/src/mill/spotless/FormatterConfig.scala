package mill.spotless

import com.diffplug.spotless.LineEnding
import upickle.default.*

import java.nio.charset.Charset
import java.nio.file.{FileSystems, PathMatcher}

case class FormatterConfig(
    includes: Seq[PathMatcher],
    steps: Seq[FormatterStepConfig],
    lineEnding: LineEnding = LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME,
    encoding: Charset = Charset.forName("UTF-8"),
    lintSuppressions: Seq[LintSuppressionConfig] = Seq()
) derives Reader {
  require(includes.nonEmpty, "includes must be non-empty")
  require(steps.nonEmpty, "steps must be non-empty")
}

private given Reader[LineEnding] = reader[String].map(LineEnding.valueOf)

private given Reader[Charset] = reader[String].map(Charset.forName)

given Reader[PathMatcher] = reader[String].map(FileSystems.getDefault.getPathMatcher)
