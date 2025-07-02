package mill.javalib.pmd

import mainargs.{Leftover, ParserForClass, arg, main}

@main(doc = "Arguments for PmdModule")
case class PmdArgs(
    @arg(name = "fail-on-violation", short = 'v', doc = "Fail if violations are found")
    failOnViolation: Boolean = true,
    @arg(name = "stdout", short = 's', doc = "Output to stdout")
    stdout: Boolean = false,
    @arg(name = "format", short = 'f', doc = "Output format (text, xml, html, etc.)")
    format: String = "text",
    @arg(doc = "Specify sources to check")
    sources: Leftover[String]
)

object PmdArgs {
  implicit val PFC: ParserForClass[PmdArgs] = ParserForClass[PmdArgs]
}
