package mill.javalib.api

import mill.api.PathRef
import mill.api.JsonFormatters._

// analysisFile is represented by os.Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: os.Path, classes: PathRef, semanticDbFiles: Option[PathRef])

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] =
    upickle.default.macroRW
}
