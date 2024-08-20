package mill.testkit


object MillTestKit {

  def defaultTargetDir: os.Path = os.temp.dir(deleteOnExit = false)

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
}
