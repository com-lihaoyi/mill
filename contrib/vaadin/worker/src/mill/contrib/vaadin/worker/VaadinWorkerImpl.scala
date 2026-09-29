package mill.contrib.vaadin.worker

import mill.contrib.vaadin.api.{FrontendBuildConfig, Logger, VaadinWorkerApi}

/**
 * Entry point of the worker classloader (see `VaadinModule.vaadinWorker`).
 *
 * The context classloader is switched to the worker's own for the duration of
 * the build: Vaadin's class finder uses it as the parent of the classloader it
 * creates over the scanned classpath.
 */
class VaadinWorkerImpl extends VaadinWorkerApi {

  override def buildFrontend(config: FrontendBuildConfig, log: Logger): Unit = {
    val thread = Thread.currentThread()
    val previous = thread.getContextClassLoader
    thread.setContextClassLoader(getClass.getClassLoader)
    try new VaadinBuildAdapter(config, log).run()
    finally thread.setContextClassLoader(previous)
  }
}
