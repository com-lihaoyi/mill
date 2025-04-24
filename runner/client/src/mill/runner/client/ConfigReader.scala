package mill.runner.client

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
import java.nio.file.Files
object ConfigReader {
  def readYaml(buildFile: java.nio.file.Path) = {
    val loaded = new Load(LoadSettings.builder().build())
      .loadFromString(mill.constants.Util.readYamlHeader(buildFile))

    loaded
  }
}
