package mill.main.gradle

import org.gradle.api.{Plugin, Project}
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import javax.inject.Inject

class GradleBuildModelPlugin @Inject (builderRegistry: ToolingModelBuilderRegistry)
    extends Plugin[Project] {

  def apply(target: Project) = builderRegistry.register(GradleBuildModelBuilder(
    ctx = GradleBuildCtx(target.getGradle),
    workspace = os.Path(target.getRootProject.getRootDir)
  ))
}
