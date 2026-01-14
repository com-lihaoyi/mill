package mill.javalib.classgraph

import mill.api.TaskCtx

trait ClassgraphWorker extends AutoCloseable {
  def discoverMainClasses(classpath: Seq[os.Path])(using ctx: TaskCtx): Seq[String]

  override def close(): Unit = {
    // noop
  }
}
