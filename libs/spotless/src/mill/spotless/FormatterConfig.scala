package mill.spotless

import com.diffplug.spotless.{Formatter, FormatterStep, LineEnding}
import upickle.default.*

import java.io.File
import java.nio.charset.{Charset, StandardCharsets}
import java.util.function.Supplier
import scala.jdk.CollectionConverters.*

/**
 * Configuration for building a [[Formatter]].
 */
case class FormatterConfig(
    lineEnding: LineEnding = LineEnding.UNIX,
    encoding: Charset = StandardCharsets.UTF_8
) derives Reader {

  def build(
      steps: Seq[FormatterStep],
      workspace: File,
      files: Seq[File]
  ): Formatter = {
    val policy = lineEnding match {
      case LineEnding.GIT_ATTRIBUTES | LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME =>
        lineEnding.createPolicy(workspace, () => files.asJava)
      case _ => lineEnding.createPolicy()
    }
    Formatter.builder()
      .encoding(encoding)
      .lineEndingsPolicy(policy)
      .steps(steps.asJava)
      .build
  }
}

object FormatterConfig {

  private given Reader[LineEnding] = reader[String].map(LineEnding.valueOf)

  private given Reader[Charset] = reader[String].map(Charset.forName)
}
