package mill.testkit

import mill.define.{Caller, Discover}


object MillTestKit {

  def defaultTargetDir: os.Path = os.temp.dir()

  def targetDir: os.Path = defaultTargetDir


  def getOutPath(testPath: Seq[String])(implicit fullName: sourcecode.FullName): os.Path = {
    getOutPathStatic() / testPath
  }

  def getOutPathStatic()(implicit fullName: sourcecode.FullName): os.Path = {
    targetDir / "workspace" / fullName.value.split('.')
  }

  def getSrcPathStatic()(implicit fullName: sourcecode.FullName): os.Path = {
    getSrcPathBase() / fullName.value.split('.')
  }
  def getSrcPathBase(): os.Path = {
    targetDir / "worksources"
  }

  class BaseModule(implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line
  ) extends mill.define.BaseModule(getSrcPathBase() / millModuleEnclosing0.value.split("\\.| |#"))(
        implicitly,
        implicitly,
        implicitly,
        Caller(null)
      ) {
    lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }
}
