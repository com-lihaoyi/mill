package mill.runner.client

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}

object ConfigReader {
  def readYaml(buildFile: java.nio.file.Path) = {
    val loaded = new Load(LoadSettings.builder().build())
      .loadFromString(mill.constants.Util.readYamlHeader(buildFile))

    loaded
  }
}
