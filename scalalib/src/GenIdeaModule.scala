package mill.scalalib

import mill.define.Command
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
  def ideaJavaModuleFacets(ideaConfigVersion: Int): Command[Seq[JavaFacet]] =
    T.command { Seq[JavaFacet]() }

  /**
   * Contribute components to idea config files.
   */
  def ideaConfigFiles(ideaConfigVersion: Int): Command[Seq[IdeaConfigFile]] =
    T.command { Seq[IdeaConfigFile]() }

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
  final case class Element(name: String,
                           attributes: Map[String, String] = Map(),
                           childs: Seq[Element] = Seq())
  object Element {
    implicit def rw: ReadWriter[Element] = macroRW
  }

  final case class JavaFacet(`type`: String, name: String, config: Element)
  object JavaFacet {
    implicit def rw: ReadWriter[JavaFacet] = macroRW
  }

  /**
   * A Idea config file contribution
   * @param name The target config file name (can be also a sub-path)
   * @param component The Idea component
   * @param config The actual (XML) configuration, encoded as [[Element]]s
   *
   * Note: the `name` fields is deprecated in favour of `subPath`, but kept for backward compatibility.
   */
  final case class IdeaConfigFile(
      name: String,
      component: String,
      config: Seq[Element]
  ) {
    // This also works as requirement check
    /** The sub-path of the config file, relative to the Idea config directory (`.idea`). */
    val subPath: SubPath = SubPath(name)
  }
  object IdeaConfigFile {

    /** Alternative creator accepting a sub-path as config file name. */
    def apply(
        subPath: SubPath,
        component: String,
        config: Seq[Element]
    ): IdeaConfigFile = IdeaConfigFile(subPath.toString(), component, config)

    implicit def rw: ReadWriter[IdeaConfigFile] = macroRW
  }

}
