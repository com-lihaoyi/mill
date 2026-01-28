package mill.api.daemon.internal

import scala.util.DynamicVariable

/**
 * Hold all evaluators from the bootstrap process.
 * Used by @allEvaluatorsCommand tasks to access evaluators from all bootstrap levels.
 */
private[mill] object AllEvaluators extends DynamicVariable[Seq[EvaluatorApi]](Seq.empty)
