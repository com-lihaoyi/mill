package mill.main.gradle;

import java.io.File;
import java.io.Serializable;
import org.gradle.api.Project;

/**
 * A model containing the settings for a Gradle project.
 * <br>
 * <b>NOTE:</b> The Gradle API used by the Gradle daemon is tied to the version of Gradle used in the target project (and not the API version used here).
 * Consequently, relatively newer features, like {@link org.gradle.api.plugins.JavaPluginExtension#getSourceSets()}} (added in Gradle 7.1), will not be available for projects that use a legacy Gradle version.
 */
public interface ProjectModel extends Serializable {

  String group();

  String name();

  String version();

  File directory();

  MavenModel maven();

  JavaModel _java();

  class Impl implements ProjectModel {

    private final String group;
    private final String name;
    private final String version;
    private final File directory;
    private final MavenModel maven;
    private final JavaModel _java;

    public Impl(
        String group,
        String name,
        String version,
        File directory,
        MavenModel maven,
        JavaModel _java) {
      this.group = group;
      this.name = name;
      this.version = version;
      this.directory = directory;
      this.maven = maven;
      this._java = _java;
    }

    public String group() {
      return group;
    }

    public String name() {
      return name;
    }

    public String version() {
      return version;
    }

    public File directory() {
      return directory;
    }

    public MavenModel maven() {
      return maven;
    }

    public JavaModel _java() {
      return _java;
    }
  }

  static ProjectModel from(Project project) {
    return new Impl(
        project.getGroup().toString(),
        project.getName(),
        project.getVersion().toString(),
        project.getProjectDir(),
        MavenModel.from(project),
        JavaModel.from(project));
  }
}
