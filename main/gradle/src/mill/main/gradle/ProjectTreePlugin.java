package mill.main.gradle;

import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

/**
 * <b>NOTE:</b>
 * The Gradle API used by the Gradle daemon is tied to the version of Gradle used in the target project
 * (and not the API version used in the classpath of this module).
 * Consequently, some features may not be available for projects that use a legacy Gradle version.
 * For example, the {@link org.gradle.api.plugins.JavaPluginExtension#getSourceSets()}} was added in Gradle `7.1`.
 */
public class ProjectTreePlugin implements Plugin<Project> {
  private final ToolingModelBuilderRegistry registry;

  @Inject()
  ProjectTreePlugin(ToolingModelBuilderRegistry registry) {
    this.registry = registry;
  }

  public void apply(Project target) {
    if (target == target.getRootProject()) {
      registry.register(new ProjectTree.Builder());
    }
  }
}
