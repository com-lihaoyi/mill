package mill.main.gradle;

import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

/**
 * A custom Gradle plugin that adds support for building a {@link ProjectTree}.
 */
public class ProjectTreePlugin implements Plugin<Project> {
  private final ToolingModelBuilderRegistry registry;

  @Inject()
  ProjectTreePlugin(ToolingModelBuilderRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void apply(Project target) {
    if (target == target.getRootProject()) {
      registry.register(new ProjectTree.Builder());
    }
  }
}
