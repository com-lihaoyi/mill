package mill.scalalib.api

import mill.define.PathRef
import mill.define.JsonFormatters._

// analysisFile is represented by os.Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: os.Path, classes: PathRef)

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] =
    upickle.default.macroRW
}
