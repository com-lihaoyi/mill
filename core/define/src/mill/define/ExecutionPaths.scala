package mill.define

import mill.api.internal
import mill.define.{NamedTask, Segment, Segments}

import java.util.regex.Matcher

case class ExecutionPaths private (dest: os.Path, meta: os.Path, log: os.Path) {}

object ExecutionPaths {

  def apply(dest: os.Path, meta: os.Path, log: os.Path): ExecutionPaths =
    new ExecutionPaths(dest, meta, log)

  def resolve(
      outPath: os.Path,
      segments: Segments
  ): ExecutionPaths = {
    val segmentStrings = segments.parts
    val targetPath = outPath / segmentStrings.map(sanitizePathSegment)
    ExecutionPaths(
      targetPath / os.up / s"${targetPath.last}.dest",
      targetPath / os.up / s"${targetPath.last}.json",
      targetPath / os.up / s"${targetPath.last}.log"
    )
  }
  def resolve(
      outPath: os.Path,
      task: NamedTask[?]
  ): ExecutionPaths = resolve(outPath, task.ctx.segments)

  // case-insensitive match on reserved names
  private val ReservedWinNames =
    raw"^([cC][oO][nN]|[pP][rR][nN]|[aA][uU][xX]|[nN][uU][lL]|[cC][oO][mM][0-9¹²³]|[lL][pP][tT][0-9¹²³])($$|[.].*$$)".r
  // Colons are not supported on Windows
  private val Colon = "[:]".r
  // Dollar sign `$` is our masking-character
  private val Dollar = "[$]".r
  // Forward-slashed are reserved for directory delimiters
  private val Slash = "/".r

  private val steps: Seq[String => String] = Seq(
    // Step 1: mask all existing dollar signs, so we can use the dollar as masking character
    s => Dollar.replaceAllIn(s, Matcher.quoteReplacement("$$")),
    // Step 2: mask reserved Windows names, like CON1 or LPT1
    _ match {
      case ReservedWinNames(keyword, rest) => s"${keyword}~${rest}"
      case s => s
    },
    // Step 3: Replace colon (:) with $colon
    s => Colon.replaceAllIn(s, Matcher.quoteReplacement("$colon")),
    // Step 4: Replace slash (/) with $slash
    s => Slash.replaceAllIn(s, Matcher.quoteReplacement("$slash"))
  )

  def sanitizePathSegment(segment: String): os.PathChunk = {
    // sanitize and implicitly convert to PathChunk
    steps.foldLeft(segment) { (segment, f) => f(segment) }
  }
}
