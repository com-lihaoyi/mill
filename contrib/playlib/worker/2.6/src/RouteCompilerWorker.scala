package mill.playlib.worker

import java.io.File

import mill.playlib.api.{RouteCompilerType, RouteCompilerWorkerApi}
import play.routes.compiler
import play.routes.compiler.RoutesCompiler.RoutesCompilerTask
import play.routes.compiler.{
  InjectedRoutesGenerator,
  RoutesCompilationError,
  RoutesCompiler,
  RoutesGenerator,
  StaticRoutesGenerator
}

private[playlib] class RouteCompilerWorker extends RouteCompilerWorkerBase {

  override def compile(
      files: Array[java.io.File],
      additionalImports: Array[String],
      forwardsRouter: Boolean,
      reverseRouter: Boolean,
      namespaceReverseRouter: Boolean,
      generatorType: RouteCompilerType,
      dest: java.io.File
  ): String = {
    generatorType match {
      case RouteCompilerType.InjectedGenerator =>
        val result = compileWithPlay(
          files.map(os.Path(_)).toSeq,
          additionalImports.toSeq,
          forwardsRouter,
          reverseRouter,
          namespaceReverseRouter,
          os.Path(dest),
          InjectedRoutesGenerator
        )
        asMillResult(result)
      case RouteCompilerType.StaticGenerator =>
        println("Static generator was deprecated in 2.6.0 and will be removed in 2.7.0")
        val result = compileWithPlay(
          files.map(os.Path(_)).toSeq,
          additionalImports.toSeq,
          forwardsRouter,
          reverseRouter,
          namespaceReverseRouter,
          os.Path(dest),
          StaticRoutesGenerator
        )
        asMillResult(result)
      case _ =>
        throw new Exception(s"Unrecognized generator type: $generatorType. Use injected or static")
    }
  }

}
