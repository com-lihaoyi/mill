package mill.launcher

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
import org.snakeyaml.engine.v2.constructor.StandardConstructor
import org.snakeyaml.engine.v2.nodes.{Node, SequenceNode, Tag}
import org.snakeyaml.engine.v2.schema.CoreSchema

object ConfigReader {
  // Custom tag for !append - just returns the sequence as-is since the launcher
  // doesn't need to process append semantics (that's handled by the evaluator)
  private val appendTag = new Tag("!append")

  def readYaml(buildFile: java.nio.file.Path, errorFileName: String) = {
    val settings = LoadSettings.builder()
      .setSchema(new CoreSchema())
      .build()

    val constructor = new StandardConstructor(settings) {
      // Handle !append tag by treating it as a regular sequence
      override def constructObject(node: Node): Object = {
        if (node.getTag == appendTag && node.isInstanceOf[SequenceNode]) {
          // Change tag to standard sequence tag and delegate
          node.setTag(Tag.SEQ)
        }
        super.constructObject(node)
      }
    }

    val loaded = new Load(settings, constructor)
      .loadFromString(mill.constants.Util.readBuildHeader(buildFile, errorFileName))

    loaded
  }
}
