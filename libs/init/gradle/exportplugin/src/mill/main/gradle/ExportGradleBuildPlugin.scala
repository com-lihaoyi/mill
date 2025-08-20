package mill.main.gradle

import org.gradle.api.{Plugin, Project}
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import javax.inject.Inject

class ExportGradleBuildPlugin @Inject (registry: ToolingModelBuilderRegistry)
    extends Plugin[Project] {

  def apply(target: Project) = registry.register(ExportGradleBuildModelBuilder(
    ctx = GradleBuildContext(gradle = target.getGradle),
    testModuleName = System.getProperty("mill.init.test.module.name"),
    workspace = os.Path(target.getRootProject.getRootDir)
  ))
}
