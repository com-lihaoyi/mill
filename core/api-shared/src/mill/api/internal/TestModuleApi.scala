package mill.api.internal

trait TestModuleApi extends ModuleApi {
  def testLocal(args: String*): TaskApi[(msg: String, results: Seq[Any])]
  private[mill] def bspBuildTargetScalaTestClasses
      : TaskApi[(frameworkName: String, classes: Seq[String])]
}
