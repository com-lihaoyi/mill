package mill.runner

import mill.define.{Caller, Discover, Segments}


object BaseModule{
  case class Info(millSourcePath0: os.Path)

  abstract class Foreign(foreign0: Option[Segments])
                        (implicit baseModuleInfo: BaseModule.Info,
                         millModuleEnclosing0: sourcecode.Enclosing,
                         millModuleLine0: sourcecode.Line,
                         millName0: sourcecode.Name,
                         millFile0: sourcecode.File)
    extends mill.define.BaseModule(baseModuleInfo.millSourcePath0, foreign0 = foreign0)(
      millModuleEnclosing0,
      millModuleLine0,
      millName0,
      millFile0,
      Caller(())
    ) with mill.main.MainModule {

    override lazy val millDiscover = Discover[this.type]

  }

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
