package mill.launcher

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}

object ConfigReader {
  def readYaml(buildFile: java.nio.file.Path, errorFileName: String) = {
    val loaded = new Load(LoadSettings.builder().build())
      .loadFromString(mill.constants.Util.readYamlHeader(buildFile, errorFileName))

    loaded
  }
}
