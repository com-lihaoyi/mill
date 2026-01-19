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

