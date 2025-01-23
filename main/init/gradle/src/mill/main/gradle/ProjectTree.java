package mill.main.gradle;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

/**
 * A Gradle project tree.
 */
public interface ProjectTree extends Serializable {

  ProjectModel project();

  List<ProjectTree> children();

  class Impl implements ProjectTree {

    private final ProjectModel project;
    private final List<ProjectTree> children;

    Impl(ProjectModel project, List<ProjectTree> children) {
      this.project = project;
      this.children = children;
    }

    public ProjectModel project() {
      return project;
    }

    public List<ProjectTree> children() {
      return children;
    }
  }

  class Builder implements ToolingModelBuilder {

    public boolean canBuild(String modelName) {
      return ProjectTree.class.getName().equals(modelName);
    }

    public Object buildAll(String modelName, Project project) {
      ProjectModel model = ProjectModel.from(project);
      List<ProjectTree> children = new LinkedList<>();
      for (Project child : project.getChildProjects().values()) {
        children.add((ProjectTree) buildAll(modelName, child));
      }
      return new ProjectTree.Impl(model, children);
    }
  }
}
