package mill.main.gradle;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.compile.JavaCompile;

public interface JavaModel extends Serializable {

  List<String> javacOptions();

  List<Config> configs();

  class Impl implements JavaModel {

    private final List<String> javacOptions;
    private final List<Config> configs;

    public Impl(List<String> javacOptions, List<Config> configs) {
      this.javacOptions = javacOptions;
      this.configs = configs;
    }

    public List<String> javacOptions() {
      return javacOptions;
    }

    public List<Config> configs() {
      return configs;
    }
  }

  static JavaModel from(Project project) {
    if (project.getPluginManager().hasPlugin("java")) {
      List<String> javacOptions = project
          .getTasks()
          .withType(JavaCompile.class)
          .named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class)
          .map(task -> task.getOptions().getAllCompilerArgs())
          .getOrElse(Collections.emptyList());
      List<Config> configs =
          project.getConfigurations().stream().map(Config::from).collect(Collectors.toList());
      return new Impl(javacOptions, configs);
    }
    return null;
  }

  interface Config extends Serializable {

    String name();

    List<Dep> deps();

    class Impl implements Config {

      private final String config;
      private final List<Dep> deps;

      public Impl(String config, List<Dep> deps) {
        this.config = config;
        this.deps = deps;
      }

      public String name() {
        return config;
      }

      public List<Dep> deps() {
        return deps;
      }
    }

    static Config from(Configuration conf) {
      String name = conf.getName();
      List<Dep> deps = conf.getDependencies().stream()
          .map(Dep::from)
          // discovered when testing project Moshi
          .filter(dep -> !(null == dep.group() && "unspecified".equals(dep.name())))
          .collect(Collectors.toList());
      return new Impl(name, deps);
    }
  }

  interface Dep extends Serializable {

    String group();

    String name();

    String version();

    class Impl implements Dep {
      private final String group;
      private final String name;
      private final String version;

      public Impl(String group, String name, String version) {
        this.group = group;
        this.name = name;
        this.version = version;
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
    }

    static Dep from(Dependency dep) {
      return new Impl(dep.getGroup(), dep.getName(), dep.getVersion());
    }
  }
}
