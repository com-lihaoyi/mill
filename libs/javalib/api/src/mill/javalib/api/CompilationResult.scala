package mill.javalib.api

import com.lihaoyi.unroll
import mill.api.PathRef
import mill.api.JsonFormatters.*

/**
 * @param analysisFile represented by os.Path, so we won't break caches after file changes
 * @param classes path to the compilation classes
 * @param semanticDbFiles path to semanticdb files, if they were produced
 */
case class CompilationResult(
    analysisFile: os.Path,
    classes: PathRef,
    @unroll semanticDbFiles: Option[PathRef] = None
)

object CompilationResult {
  implicit val jsonFormatter: upickle.ReadWriter[CompilationResult] =
    upickle.macroRW
}
