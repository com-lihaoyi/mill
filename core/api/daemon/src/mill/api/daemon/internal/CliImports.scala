package mill.api.daemon.internal

import scala.util.DynamicVariable

/**
 * Hold additional runtime dependencies given via the `--import` cli option.
 */
private[mill] object CliImports extends DynamicVariable[Seq[String]](Seq.empty)
