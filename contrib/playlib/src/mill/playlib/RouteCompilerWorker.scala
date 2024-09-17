package mill.playlib

import mill.api.{Ctx, PathRef, Result}
import mill.playlib.api.{RouteCompilerType, RouteCompilerWorkerApi}
import mill.scalalib.api.CompilationResult
import mill.{Agg, T}

private[playlib] class RouteCompilerWorker extends AutoCloseable {

  private var routeCompilerInstanceCache =
    Option.empty[(Long, mill.playlib.api.RouteCompilerWorkerApi)]

  protected def bridge(toolsClasspath: Agg[PathRef])(
      implicit ctx: Ctx
  ): RouteCompilerWorkerApi = {
    val classloaderSig = toolsClasspath.hashCode
    routeCompilerInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val toolsClassPath = toolsClasspath.map(_.path.toIO.toURI.toURL).toVector
        ctx.log.debug("Loading classes from\n" + toolsClassPath.mkString("\n"))
        val cl = mill.api.ClassLoader.create(
          toolsClassPath,
          null,
          sharedLoader = getClass().getClassLoader(),
          sharedPrefixes = Seq("mill.playlib.api."),
          logger = Some(ctx.log)
        )
        val bridge = cl
          .loadClass("mill.playlib.worker.RouteCompilerWorker")
          .newInstance()
          .asInstanceOf[mill.playlib.api.RouteCompilerWorkerApi]
        routeCompilerInstanceCache = Some((classloaderSig, bridge))
        bridge
    }
  }

  def compile(
      routerClasspath: Agg[PathRef],
      files: Seq[os.Path],
      additionalImports: Seq[String],
      forwardsRouter: Boolean,
      reverseRouter: Boolean,
      namespaceReverseRouter: Boolean,
      generatorType: RouteCompilerType,
      dest: os.Path
  )(implicit ctx: Ctx): Result[CompilationResult] = {
    // the routes file must come last as it can include the routers generated
    // by the others
    bridge(routerClasspath)
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
        Result.Success(CompilationResult(T.dest / "zinc", PathRef(T.dest)))
      case err => Result.Failure(err)
    }
  }

  override def close(): Unit = {
    routeCompilerInstanceCache = None
  }
}
