package mill.scalalib

import mill.define.Command
import mill.{Module, T}

/**
 * Module specific configuration of the Idea project file generator.
 */
trait GenIdeaModule extends Module {
  import GenIdeaModule._

  /**
    * Skip Idea project file generation.
    */
  def skipIdea: Boolean = false

  /**
    * Contribute facets to the Java module configuration.
    * @param ideaConfigVersion The IDEA configuration version in use. Probably `4`.
    * @return
    */
  def ideaJavaModuleFacets(ideaConfigVersion: Int): Command[Seq[JavaFacet]] = T.command { Seq[JavaFacet]() }

  /**
    * Contribute components to idea config files.
    */
  def ideaConfigFiles(ideaConfigVersion: Int): Command[Seq[IdeaConfigFile]] = T.command { Seq[IdeaConfigFile]() }

  }

object GenIdeaModule {
  import upickle.default._

  final case class JavaFacet(`type`: String, name: String, body: String)
  object JavaFacet {
    implicit def rw: ReadWriter[JavaFacet] = macroRW
  }

  case class Element(name: String, attributes: Map[String, String] = Map(), childs: Seq[Element] = Seq())
  object Element {
    implicit def rw: ReadWriter[Element] = macroRW
  }


  sealed trait IdeaConfigFile {
    def name: String
    def component: String
  }

  object IdeaConfigFile {
    implicit def rw: ReadWriter[IdeaConfigFile] = macroRW
  }

  final case class SimpleIdeaConfigFile(name: String, component: String, options: Map[String, String]) extends IdeaConfigFile

  object SimpleIdeaConfigFile {
    implicit def rw: ReadWriter[SimpleIdeaConfigFile] = macroRW
  }

}
