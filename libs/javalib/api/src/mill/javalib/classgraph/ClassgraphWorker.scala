package mill.javalib.classgraph

import mill.api.TaskCtx

trait ClassgraphWorker {
  def discoverMainClasses(classpath: Seq[os.Path])(using ctx: TaskCtx): Seq[String]
}
