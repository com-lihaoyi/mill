package mill.init.migrate
package sbt

import _root_.sbt._

/**
 * SBT ''apply'' script that adds [[PluginKeys]] to a session.
 *
 * =Usage=
 * Setting for input arguments must be defined before
 * [[https://github.com/JetBrains/sbt-structure/#sideloading sideloading]].
 * {{{
 *   set SettingKey[(String, File)]("millInitExtractArgs") in Global := <tuple>
 *   apply -cp <path-to-jar> mill.init.migrate.sbt.ApplyExtract
 *   millInitExtract
 * }}}
 */
object ApplyExtract extends (State => State) {

  def apply(state: State) = {
    val extracted = Project.extract(state)
    import extracted._

    val globalSettings = Seq(
      ExtractKeys.millInitExtract := ExtractTasks.extract.value
    )
    val projectSettings = Seq(
      ExtractKeys.millInitMetaModule := ExtractTasks.module.value
    )

    val sessionSettings = inScope(GlobalScope)(globalSettings) ++
      structure.allProjectRefs.flatMap(ref =>
        Project.transform(
          Scope.resolveScope(Scope(Select(ref), Zero, Zero, Zero), ref.build, rootProject),
          projectSettings
        )
      )
    BuiltinCommands.reapply(session.appendRaw(sessionSettings), structure, state)
  }
}
