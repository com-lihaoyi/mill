package mill
package playlib
package worker

import java.io.File

import mill.playlib.api.{RouteCompilerType, RouteCompilerWorkerApi}
import play.routes.compiler
import play.routes.compiler.RoutesCompiler.RoutesCompilerTask
import play.routes.compiler.{InjectedRoutesGenerator, RoutesCompilationError, RoutesCompiler, RoutesGenerator}


private[playlib] class RouteCompilerWorker extends RouteCompilerWorkerApi {

  override def compile(files: Array[java.io.File],
                       additionalImports: Array[String],
                       forwardsRouter: Boolean,
                       reverseRouter: Boolean,
                       namespaceReverseRouter: Boolean,
                       generatorType: RouteCompilerType,
                       dest: java.io.File): String = {
    generatorType match {
      case RouteCompilerType.InjectedGenerator =>
        val result = compileWithPlay(files.map(os.Path(_)), additionalImports, forwardsRouter, reverseRouter,
          namespaceReverseRouter, os.Path(dest), InjectedRoutesGenerator)
        asMillResult(result)
      case RouteCompilerType.StaticGenerator =>
        "Static generator was deprecated in 2.6.0 then removed in 2.7.x, see https://www.playframework.com/documentation/2.7.x/Migration27#StaticRoutesGenerator-removed"
    }
  }

  // the following code is duplicated between play worker versions because it depends on play types
  // which are not guaranteed to stay the same between versions even though they are currently
  // identical
  private def compileWithPlay(files: Seq[os.Path],
                              additionalImports: Seq[String],
                              forwardsRouter: Boolean,
                              reverseRouter: Boolean,
                              namespaceReverseRouter: Boolean,
                              dest: os.Path,
                              routesGenerator: RoutesGenerator): Either[Seq[compiler.RoutesCompilationError], Seq[File]] = {
    val seed: Either[Seq[compiler.RoutesCompilationError], List[File]] = Right(List.empty[File])
    files.map(file => compileWithPlay(file, additionalImports.toSeq, forwardsRouter, reverseRouter,
      namespaceReverseRouter, dest, routesGenerator)).foldLeft(seed) {
      case (Right(accFiles), Right(files)) => Right(accFiles ++ files)
      case (Right(accFiles), Left(errors)) => Left(errors)
      case (left@Left(errors), _) => left
    }
  }

  private def compileWithPlay(file: os.Path,
                              additionalImports: Seq[String],
                              forwardsRouter: Boolean,
                              reverseRouter: Boolean,
                              namespaceReverseRouter: Boolean,
                              dest: os.Path,
                              routesGenerator: RoutesGenerator) = {
    val result =
      RoutesCompiler.compile(
        RoutesCompilerTask(file.toIO, additionalImports, forwardsRouter, reverseRouter,
          namespaceReverseRouter),
        generator = routesGenerator,
        generatedDir = dest.toIO
      )
    result
  }

  private def asMillResult(result: Either[Seq[RoutesCompilationError], Seq[File]]): String = {
    result match {
      case Right(_) => null
      case Left(errors) =>
        val errorMsg = errors.map(error =>
          s"compilation error in ${error.source.getPath} at line ${error.line.getOrElse("?")}, " +
            s"column ${error.column.getOrElse("?")}: ${error.message}")
          .mkString("\n")
        "Unable to compile play routes, " + errorMsg
    }
  }
}
