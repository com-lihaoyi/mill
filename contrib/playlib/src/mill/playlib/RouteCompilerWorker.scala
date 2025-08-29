package mill.playlib

import mill.api.{TaskCtx, PathRef}
import mill.api.Result
import mill.playlib.api.RouteCompilerType
import mill.javalib.api.CompilationResult
import mill.*

private[playlib] class RouteCompilerWorker {

  def compile(
      toolsClasspath: Seq[PathRef],
      files: Seq[os.Path],
      additionalImports: Seq[String],
      forwardsRouter: Boolean,
      reverseRouter: Boolean,
      namespaceReverseRouter: Boolean,
      generatorType: RouteCompilerType,
      dest: os.Path
  )(implicit ctx: TaskCtx): Result[CompilationResult] = {
    // the routes file must come last as it can include the routers generated
    // by the others
    val toolsClassPath = toolsClasspath.map(_.path).toVector
    ctx.log.debug("Loading classes from\n" + toolsClassPath.mkString("\n"))
    mill.util.Jvm.withClassLoader(
      toolsClassPath,
      null,
      sharedLoader = getClass().getClassLoader(),
      sharedPrefixes = Seq("mill.playlib.api.")
    ) { cl =>
      val bridge = cl
        .loadClass("mill.playlib.worker.RouteCompilerWorker")
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[mill.playlib.api.RouteCompilerWorkerApi]
      bridge
        .compile(
          files.toArray.map(_.toIO),
          additionalImports.toArray,
          forwardsRouter,
          reverseRouter,
          namespaceReverseRouter,
          generatorType,
          dest.toIO
        ) match {
        case null =>
          Result.Success(CompilationResult(Task.dest / "zinc", PathRef(Task.dest), semanticDbFiles = None))
        case err => Result.Failure(err)
      }
    }

  }
}
