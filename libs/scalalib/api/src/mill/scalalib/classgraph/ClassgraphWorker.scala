package mill.scalalib.classgraph

import mill.api.TaskCtx

trait ClassgraphWorker {
  def discoverMainClasses(classpath: Seq[os.Path])(implicit ctx: TaskCtx): Seq[String]
}
