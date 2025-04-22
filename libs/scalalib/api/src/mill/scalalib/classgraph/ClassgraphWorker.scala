package mill.scalalib.classgraph

import mill.define.TaskCtx

trait ClassgraphWorker {
  def discoverMainClasses(classpath: Seq[os.Path])(implicit ctx: TaskCtx): Seq[String]
}
