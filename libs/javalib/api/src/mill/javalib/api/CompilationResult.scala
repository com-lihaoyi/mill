package mill.javalib.api

import mill.api.PathRef
import mill.api.JsonFormatters._

/**
 * @param analysisFile represented by os.Path, so we won't break caches after file changes
 * @param classes path to the compilation classes
 * @param semanticDbFiles path to semanticdb files, if they were produced
 */
case class CompilationResult(
    analysisFile: os.Path,
    classes: PathRef,
    semanticDbFiles: Option[PathRef]
)

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] =
    upickle.default.macroRW
}
