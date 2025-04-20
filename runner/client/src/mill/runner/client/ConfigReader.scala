package mill.runner.client

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
import java.nio.file.Files
object ConfigReader {
  def readYaml(buildFile: java.nio.file.Path) = {
    val yamlString = String.join(
      "\n",
      Files
        .readAllLines(buildFile)
        .stream()
        .takeWhile(_.startsWith("//|"))
        .map{case s"//|$rest" => rest}
        .toArray(n => new Array[String](n))
    )

    
    val loaded = new Load(LoadSettings.builder().build())
      .loadFromString(yamlString)
  }

}
