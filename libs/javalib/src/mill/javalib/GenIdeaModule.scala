package mill.javalib

import mill.api.Task
import mill.Module
import mill.api.daemon.internal.idea.{GenIdeaModuleApi, IdeaConfigFile, JavaFacet}

/**
 * Module specific configuration of the Idea project file generator.
 */
trait GenIdeaModule extends Module with GenIdeaModuleApi {

  /**
   * The path denoting the module directory in generated IntelliJ projects. Defaults to [[moduleDir]].
   */
  override def intellijModulePathJava: java.nio.file.Path = moduleDir.toNIO

  /**
   * Skip Idea project file generation.
   */
  override def skipIdea: Boolean = false

  /**
   * Contribute facets to the Java module configuration.
   * @param ideaConfigVersion The IDEA configuration version in use. Probably `4`.
   * @return
   */
  def ideaJavaModuleFacets(ideaConfigVersion: Int): Task[Seq[JavaFacet]] =
    Task.Anon { Seq[JavaFacet]() }

  /**
   * Contribute components to idea config files.
   */
  def ideaConfigFiles(ideaConfigVersion: Int): Task[Seq[IdeaConfigFile]] =
    Task.Anon { Seq[IdeaConfigFile]() }

}
