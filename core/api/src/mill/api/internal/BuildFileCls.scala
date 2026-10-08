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
    val instance = cls.getField("MODULE$").get(null)
    // Publish the root module so user-facing code (e.g. plugins) can access it
    // via `BuildCtx.rootModule` without having to thread an `Evaluator` through.
    BuildCtx.rootModule0 = instance.asInstanceOf[mill.api.daemon.internal.BaseModuleApi]
    instance.asInstanceOf[mill.api.daemon.internal.RootModuleApi]
  }

  def moduleWatchedValues = BuildCtx.watchedValues.toSeq
  def withEvalWatchedValues[T](body: => T): (T, Seq[mill.api.daemon.Watchable]) = {
    val (result, buf) = BuildCtx.withEvalWatchedValues(body)
    (result, buf.toSeq)
  }
}
