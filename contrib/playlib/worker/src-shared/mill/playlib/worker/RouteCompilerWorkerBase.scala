package mill.playlib.worker

import java.io.File

import mill.playlib.api.{RouteCompilerType, RouteCompilerWorkerApi}
import play.routes.compiler
import play.routes.compiler.RoutesCompiler.RoutesCompilerTask
import play.routes.compiler.{
  InjectedRoutesGenerator,
  RoutesCompilationError,
  RoutesCompiler,
  RoutesGenerator
}

/**
 * Shared base class for play workers.
 *  Needs to compile with Scala 2.12 (Play 2.6) and Scala 2.13.
 *  Feel free to override or not use if newer play versions introduce incompatible API types.
 */
protected[playlib] class RouteCompilerWorkerBase extends RouteCompilerWorkerApi {

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
          files.toIndexedSeq.map(os.Path(_)),
          additionalImports.toIndexedSeq,
          forwardsRouter,
          reverseRouter,
          namespaceReverseRouter,
          os.Path(dest),
          InjectedRoutesGenerator
        )
        asMillResult(result)
      case RouteCompilerType.StaticGenerator =>
        "Static generator was deprecated in 2.6.0 then removed in 2.7.x, see https://www.playframework.com/documentation/2.7.x/Migration27#StaticRoutesGenerator-removed"
    }
  }

  protected def compileWithPlay(
      files: Seq[os.Path],
      additionalImports: Seq[String],
      forwardsRouter: Boolean,
      reverseRouter: Boolean,
      namespaceReverseRouter: Boolean,
      dest: os.Path,
      routesGenerator: RoutesGenerator
  ): Either[Seq[compiler.RoutesCompilationError], Seq[File]] = {
    val seed: Either[Seq[compiler.RoutesCompilationError], List[File]] = Right(List.empty[File])
    files.map(file =>
      compileWithPlay(
        file,
        additionalImports.toSeq,
        forwardsRouter,
        reverseRouter,
        namespaceReverseRouter,
        dest,
        routesGenerator
      )
    ).foldLeft(seed) {
      case (Right(accFiles), Right(files)) => Right(accFiles ++ files)
      case (Right(_), Left(errors)) => Left(errors)
      case (left @ Left(_), _) => left
    }
  }

  protected def compileWithPlay(
      file: os.Path,
      additionalImports: Seq[String],
      forwardsRouter: Boolean,
      reverseRouter: Boolean,
      namespaceReverseRouter: Boolean,
      dest: os.Path,
      routesGenerator: RoutesGenerator
  ): Either[Seq[RoutesCompilationError], Seq[File]] = {
    val result =
      RoutesCompiler.compile(
        new RoutesCompilerTask(
          file.toIO,
          additionalImports,
          forwardsRouter,
          reverseRouter,
          namespaceReverseRouter
        ),
        generator = routesGenerator,
        generatedDir = dest.toIO
      )
    result
  }

  protected def asMillResult(result: Either[Seq[RoutesCompilationError], Seq[File]]): String = {
    result match {
      case Right(_) => null
      case Left(errors) =>
        val errorMsg = errors.map(error =>
          s"compilation error in ${error.source.getPath} at line ${error.line.getOrElse("?")}, " +
            s"column ${error.column.getOrElse("?")}: ${error.message}"
        )
          .mkString("\n")
        "Unable to compile play routes, " + errorMsg
    }
  }
}
