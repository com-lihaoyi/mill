package mill.scalalib

import mill.define.Task
import mill.{Module, PathRef, T}
import os.SubPath
import mill.runner.api.{JavaFacet, IdeaConfigFile}

/**
 * Module specific configuration of the Idea project file generator.
 */
trait GenIdeaModule extends Module {

  def intellijModulePath: os.Path = moduleDir
  def intellijModulePathJava: java.nio.file.Path = intellijModulePath.toNIO

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
    Task.Anon { Seq[JavaFacet]() }

  /**
   * Contribute components to idea config files.
   */
  def ideaConfigFiles(ideaConfigVersion: Int): Task[Seq[IdeaConfigFile]] =
    Task.Anon { Seq[IdeaConfigFile]() }

  def ideaCompileOutput: T[PathRef] = Task(persistent = true) {
    PathRef(Task.dest / "classes")
  }

}
