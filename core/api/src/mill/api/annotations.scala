package mill.api

import scala.annotation.StaticAnnotation

/**
 * Annotation to mark a command as not requiring bootstrapping.
 *
 * Commands marked with this annotation can be run even when the root build.mill
 * has compile/runtime errors, as long as the meta-build level compiles successfully.
 *
 * This is useful for commands like `version`, `shutdown`, `clean`, `init` that
 * don't need the full build to work.
 *
 * Usage:
 * {{{
 * @nonBootstrapped
 * def version(): Command[String] = Task.Command(exclusive = true) {
 *   BuildInfo.millVersion
 * }
 * }}}
 */
final class nonBootstrapped extends StaticAnnotation

/**
 * Annotation to mark a command that needs access to all evaluators from the bootstrap process.
 *
 * Commands marked with this annotation will:
 * 1. Allow the bootstrap process to complete (success or failure)
 * 2. Receive the full list of evaluators from all bootstrap levels
 * 3. Execute after bootstrapping with access to all evaluators
 *
 * The command must accept a `Seq[EvaluatorApi]` parameter containing evaluators
 * from all bootstrap depths.
 *
 * This is useful for IDE integration commands like `GenIdea` and `GenEclipse` that
 * need to analyze modules across all build levels.
 *
 * Usage:
 * {{{
 * @allEvaluatorsCommand
 * def idea(evaluators: Seq[EvaluatorApi]): Command[Unit] = Task.Command(exclusive = true) {
 *   new GenIdeaImpl(evaluators).run()
 * }
 * }}}
 */
final class allEvaluatorsCommand extends StaticAnnotation
