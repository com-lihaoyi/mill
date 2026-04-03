package mill.main.gradle

import org.gradle.api.{Plugin, Project}
import org.gradle.api.model.ObjectFactory
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import javax.inject.Inject

class BuildModelPlugin @Inject (
    builderRegistry: ToolingModelBuilderRegistry,
    objectFactory: ObjectFactory
) extends Plugin[Project] {

  def apply(target: Project) = builderRegistry.register(BuildModelBuilder(
    ctx = GradleBuildCtx(target.getGradle),
    objectFactory = objectFactory,
    workspace = os.Path(target.getRootDir)
  ))
}
