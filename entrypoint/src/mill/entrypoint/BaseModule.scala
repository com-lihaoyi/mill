package mill.entrypoint

import mill.define.{Caller, Discover}


object BaseModule{
  case class Info(millSourcePath0: os.Path)
}
abstract class BaseModule()
                         (implicit baseModuleInfo: BaseModule.Info,
                          millModuleEnclosing0: sourcecode.Enclosing,
                          millModuleLine0: sourcecode.Line,
                          millName0: sourcecode.Name,
                          millFile0: sourcecode.File)
  extends mill.define.BaseModule(baseModuleInfo.millSourcePath0)(
    millModuleEnclosing0,
    millModuleLine0,
    millName0,
    millFile0,
    Caller(())
  ) with mill.main.MainModule{

  override lazy val millDiscover = Discover[this.type]

}
