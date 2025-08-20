package mill.main.gradle;

import java.io.Serializable;

public interface ExportGradleBuildModel extends Serializable {
  String getModulesJson();

  class Impl implements ExportGradleBuildModel {
    private final String modulesJson;

    public Impl(String modulesJson) {
      this.modulesJson = modulesJson;
    }

    @Override
    public String getModulesJson() {
      return modulesJson;
    }
  }
}
