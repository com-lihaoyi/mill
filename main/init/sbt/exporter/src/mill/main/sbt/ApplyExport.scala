package mill.main.sbt

import sbt.{BuiltinCommands, GlobalScope, Project, ProjectRef, Scope, Select, State, Zero}

/**
 * SBT `apply` script that adds [[ExportKeys]] settings to a session.
 *
 * =Usage=
 * [[ExportKeys.millInitExportFile]] must be defined before
 * [[https://github.com/JetBrains/sbt-structure/#sideloading sideloading]].
 * {{{
 *   set SettingKey[File]("millInitExportFile") in Global := file(<path-to-file>)
 *   apply -cp <path-to-jar> mill.main.sbt.ApplyExport
 *   millInitExport
 * }}}
 */
object ApplyExport extends (State => State) {

  // https://github.com/JetBrains/sbt-structure/blob/master/extractor/src/main/scala/org/jetbrains/sbt/CreateTasks.scala
  def apply(state: State) = {
    val globalSettings = Seq(
      ExportKeys.millInitExport := ExportTasks.millInitExport.value
    )
    val projectSettings = Seq(
      ExportKeys.millInitProjectModel := ExportTasks.millInitProjectModel.value
    )
    val extracted = Project.extract(state)
    def transformProject(ref: ProjectRef) = {
      val projectScope = Scope(Select(ref), Zero, Zero, Zero)
      val transformScope = Scope.resolveScope(projectScope, ref.build, extracted.rootProject)
      Project.transform(transformScope, projectSettings)
    }
    val exportSettings =
      sbt.inScope(GlobalScope)(globalSettings) ++
        extracted.structure.allProjectRefs.flatMap(transformProject)
    BuiltinCommands.reapply(extracted.session.appendRaw(exportSettings), extracted.structure, state)
  }
}
