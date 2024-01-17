package mill.runner

import scala.util.DynamicVariable

/**
 * Hold additional runtime dependencies given via the `--import` cli option.
 */
private[runner] object CliImports extends DynamicVariable[Seq[String]](Seq.empty)
