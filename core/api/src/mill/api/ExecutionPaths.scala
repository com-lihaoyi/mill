package mill.api

import java.util.regex.Matcher

final case class ExecutionPaths private[mill] (dest: os.Path, meta: os.Path, log: os.Path) {}

/**
 * Logic to resolve a [[Task]]'s [[Segments]] to the various paths on disk owned by that task.
 */
object ExecutionPaths {

  def resolve(
      outPath: os.Path,
      segments: Segments
  ): ExecutionPaths = {
    val segmentStrings = segments.parts
    val taskPath = outPath / segmentStrings.map(sanitizePathSegment)
    ExecutionPaths(
      taskPath / os.up / s"${taskPath.last}.dest",
      taskPath / os.up / s"${taskPath.last}.json",
      taskPath / os.up / s"${taskPath.last}.log"
    )
  }

  def resolve(
      outPath: os.Path,
      task: Task.Named[?]
  ): ExecutionPaths = resolve(outPath, task.ctx.segments)

  // case-insensitive match on reserved names
  private val ReservedWinNames =
    raw"^([cC][oO][nN]|[pP][rR][nN]|[aA][uU][xX]|[nN][uU][lL]|[cC][oO][mM][0-9¹²³]|[lL][pP][tT][0-9¹²³])($$|[.].*$$)".r
  // Colons are not supported on Windows
  private val Colon = "[:]".r
  // Dollar sign `$` is our masking-character
  private val Dollar = "[$]".r
  // Forward-slashed are reserved for directory delimiters

  private val steps: Seq[String => String] = Seq(
    // Step 1: mask all existing dollar signs, so we can use the dollar as masking character
    s => Dollar.replaceAllIn(s, Matcher.quoteReplacement("$$")),
    // Step 2: mask reserved Windows names, like CON1 or LPT1
    _ match {
      case ReservedWinNames(keyword, rest) => s"${keyword}~${rest}"
      case s => s
    },
    // Step 3: Replace colon (:) with $colon
    s => Colon.replaceAllIn(s, Matcher.quoteReplacement("$colon"))
  )

  def sanitizePathSegment(segment: String): os.PathChunk = {
    // sanitize and implicitly convert to PathChunk
    os.SubPath(steps.foldLeft(segment) { (segment, f) => f(segment) })
  }
}
