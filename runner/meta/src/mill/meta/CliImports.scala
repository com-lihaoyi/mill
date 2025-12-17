package mill.meta

import scala.util.DynamicVariable

/**
 * Hold additional runtime dependencies given via the `--import` cli option.
 */
object CliImports extends DynamicVariable[Seq[String]](Seq.empty)
