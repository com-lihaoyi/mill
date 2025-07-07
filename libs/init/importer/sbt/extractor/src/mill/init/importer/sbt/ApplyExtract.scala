package mill.init.importer
package sbt

import _root_.sbt._

/**
 * SBT `apply` script that adds [[ExtractKeys]] settings to a session.
 *
 * =Usage=
 * [[ExtractKeys.millInitExtractFile]] must be defined before
 * [[https://github.com/JetBrains/sbt-structure/#sideloading sideloading]].
 * {{{
 *   set SettingKey[File]("millInitExtractFile") in Global := file(<path-to-file>)
 *   apply -cp <path-to-jar> mill.init.importer.sbt.ApplyExtract
 *   millInitExtract
 * }}}
 */
object ApplyExtract extends (State => State) {

  def apply(state: State) = {
    val extracted = Project.extract(state)
    def resolveScope(ref: ProjectRef) = Scope.resolveScope(
      Scope(Select(ref), Zero, Zero, Zero),
      ref.build,
      extracted.rootProject
    )

    val globalSettings = Seq(
      ExtractKeys.millInitExtract := ExtractTasks.millInitExtract.value
    )
    val projectSettings = Seq(
      ExtractKeys.millInitSbtProjectIR := ExtractTasks.millInitSbtProjectIR.value
    )
    val sessionSettings = (inScope(GlobalScope)(globalSettings)
      ++ extracted.structure.allProjectRefs.flatMap(p =>
        Project.transform(resolveScope(p), projectSettings)
      ))
    BuiltinCommands.reapply(
      extracted.session.appendRaw(sessionSettings),
      extracted.structure,
      state
    )
  }
}
