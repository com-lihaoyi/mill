package mill.scalalib

import mill.define.Task
import mill.{Module, PathRef, T}
import os.SubPath

/**
 * Module specific configuration of the Idea project file generator.
 */
trait GenIdeaModule extends Module {
  import GenIdeaModule._

  def intellijModulePath: os.Path = millSourcePath

  /**
   * Skip Idea project file generation.
   */
  def skipIdea: Boolean = false

  /**
   * Contribute facets to the Java module configuration.
   * @param ideaConfigVersion The IDEA configuration version in use. Probably `4`.
   * @return
   */
  def ideaJavaModuleFacets(ideaConfigVersion: Int): Task[Seq[JavaFacet]] =
    T.task { Seq[JavaFacet]() }

  /**
   * Contribute components to idea config files.
   */
  def ideaConfigFiles(ideaConfigVersion: Int): Task[Seq[IdeaConfigFile]] =
    T.task { Seq[IdeaConfigFile]() }

  def ideaCompileOutput: T[PathRef] = T.persistent {
    PathRef(T.dest / "classes")
  }

}

object GenIdeaModule {
  import upickle.default._

  /**
   * Encoding of an Idea XML configuraiton fragment.
   * @param name The XML element name
   * @param attributes The optional XML element attributes
   * @param childs The optional XML child elements.
   */
  final case class Element(
      name: String,
      attributes: Map[String, String] = Map(),
      childs: Seq[Element] = Seq()
  )
  object Element {
    implicit def rw: ReadWriter[Element] = macroRW
  }

  final case class JavaFacet(`type`: String, name: String, config: Element)
  object JavaFacet {
    implicit def rw: ReadWriter[JavaFacet] = macroRW
  }

  /**
   * A Idea config file contribution
   * @param subPath The sub-path of the config file, relative to the Idea config directory (`.idea`)
   * @param component The Idea component
   * @param config The actual (XML) configuration, encoded as [[Element]]s
   *
   * Note: the `name` fields is deprecated in favour of `subPath`, but kept for backward compatibility.
   */
  final case class IdeaConfigFile(
      subPath: SubPath,
      component: Option[String],
      config: Seq[Element]
  ) {
    // An empty component name meas we contribute a whole file
    // If we have a fill file, we only accept a single root xml node.
    require(
      component.forall(_.nonEmpty) && (component.nonEmpty || config.size == 1),
      "Files contributions must have exactly one root element."
    )

    def asWholeFile: Option[(SubPath, Element)] =
      if (component.isEmpty) {
        Option(subPath -> config.head)
      } else None
  }
  object IdeaConfigFile {

    /** Alternative creator accepting a component string. */
    def apply(
        subPath: SubPath,
        component: String,
        config: Seq[Element]
    ): IdeaConfigFile = IdeaConfigFile(subPath, if (component == "") None else Option(component), config)

    implicit def rw: ReadWriter[IdeaConfigFile] = macroRW
  }

}
