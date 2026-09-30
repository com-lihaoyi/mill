package mill.contrib.vaadin.api

/**
 * Runs Vaadin's production frontend build. Implemented by the
 * `mill-contrib-vaadin-worker` artifact, which is loaded in its own classloader
 * together with Vaadin's build tooling; only this package and the Scala library
 * are shared with the build's classloader.
 */
trait VaadinWorker extends AutoCloseable {
  def buildFrontend(config: FrontendBuildConfig, log: VaadinLogger): Unit

  override def close(): Unit = {}
}
