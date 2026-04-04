package mill.api.internal

import mill.api.BuildCtx
import mill.constants.CodeGenConstants.{globalPackagePrefix, wrapperObjectName}

class BuildFileCls(classLoader: ClassLoader)
    extends mill.api.daemon.internal.BuildFileApi {
  def checker = mill.api.internal.ResolveChecker(BuildCtx.workspaceRoot)
  val rootModule = os.checker.withValue(checker) {
    val cls =
      try classLoader.loadClass(s"$globalPackagePrefix.${wrapperObjectName}$$")
      catch {
        case _: ClassNotFoundException =>
          classLoader.loadClass("mill.util.internal.DummyModule$")
      }
    cls.getField("MODULE$").get(null).asInstanceOf[mill.api.daemon.internal.RootModuleApi]
  }

  def moduleWatchedValues = BuildCtx.watchedValues.toSeq
  def evalWatchedValues = BuildCtx.evalWatchedValues
}
