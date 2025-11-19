package mill.groovylib

import mill.*
import mill.api.{Discover, ExternalModule, TaskCtx}
import mill.groovylib.worker.api.GroovyWorker
import mill.util.ClassLoaderCachedFactory

@mill.api.experimental
class GroovyWorkerManager()(implicit ctx: TaskCtx)
    extends ClassLoaderCachedFactory[GroovyWorker](ctx.jobs) {

  def getValue(cl: ClassLoader) = GroovyWorkerManager.get(cl)
}

object GroovyWorkerManager extends ExternalModule {
  def groovyWorker: Worker[GroovyWorkerManager] = Task.Worker {
    new GroovyWorkerManager()
  }

  def get(toolsClassLoader: ClassLoader)(implicit ctx: TaskCtx): GroovyWorker = {
    // TODO why not use ServiceLoader...investigate
    val className =
      classOf[GroovyWorker].getPackage().getName().split("\\.").dropRight(1).mkString(
        "."
      ) + ".impl." + classOf[GroovyWorker].getSimpleName() + "Impl"

    val impl = toolsClassLoader.loadClass(className)
    val worker = impl.getConstructor().newInstance().asInstanceOf[GroovyWorker]
    if (worker.getClass().getClassLoader() != toolsClassLoader) {
      ctx.log.warn(
        """Worker not loaded from worker classloader.
          |You should not add the mill-groovy-worker JAR to the mill build classpath""".stripMargin
      )
    }
    if (worker.getClass().getClassLoader() == classOf[GroovyWorker].getClassLoader()) {
      ctx.log.warn("Worker classloader used to load interface and implementation")
    }
    worker
  }

  override def millDiscover: Discover = Discover[this.type]
}
