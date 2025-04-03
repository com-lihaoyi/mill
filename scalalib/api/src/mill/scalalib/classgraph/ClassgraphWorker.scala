package mill.scalalib.classgraph

import mill.api.Ctx

trait ClassgraphWorker {
  def discoverMainClasses(classpath: Seq[os.Path])(implicit ctx: Ctx): Seq[String]
}
