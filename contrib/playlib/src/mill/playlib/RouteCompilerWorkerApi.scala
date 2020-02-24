package mill
package playlib

import mill.api.{Ctx, Result}
import mill.define.{Discover, ExternalModule, Worker}
import mill.playlib.api.RouteCompilerType
import mill.scalalib.api.CompilationResult

private[playlib] class RouteCompilerWorker {
  private var routeCompilerInstanceCache = Option.empty[(Long, mill.playlib.api.RouteCompilerWorkerApi)]

  private def bridge(toolsClasspath: Agg[os.Path])
                    (implicit ctx: Ctx) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    routeCompilerInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val toolsClassPath = toolsClasspath.map(_.toIO.toURI.toURL).toVector
        ctx.log.debug("Loading classes from\n"+toolsClassPath.mkString("\n"))
        val cl = mill.api.ClassLoader.create(
          toolsClassPath,
          getClass.getClassLoader
        )
        val bridge = cl
          .loadClass("mill.playlib.worker.RouteCompilerWorker")
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[mill.playlib.api.RouteCompilerWorkerApi]
        routeCompilerInstanceCache = Some((classloaderSig, bridge))
        bridge
    }
  }


  def compile(routerClasspath: Agg[os.Path],
              files: Seq[os.Path],
              additionalImports: Seq[String],
              forwardsRouter: Boolean,
              reverseRouter: Boolean,
              namespaceReverseRouter: Boolean,
              generatorType: RouteCompilerType,
              dest: os.Path)(implicit ctx: Ctx)
  : Result[CompilationResult] = {
    //the routes file must come last as it can include the routers generated
    //by the others
    bridge(routerClasspath)
      .compile(
        files,
        additionalImports,
        forwardsRouter,
        reverseRouter,
        namespaceReverseRouter,
        generatorType,
        dest
      )(ctx)
  }



}

private[playlib] object RouteCompilerWorkerModule extends ExternalModule {
  def routeCompilerWorker: Worker[RouteCompilerWorker] = T.worker {
    new RouteCompilerWorker()
  }

  lazy val millDiscover = Discover[this.type]
}

