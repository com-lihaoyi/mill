package mill.runner.worker

import mill.define.{Discover, Worker}
import mill.{Agg, PathRef, Task, Module, T}
import mill.runner.worker.{api => workerApi}
import mill.api.Result

import java.util.concurrent.atomic.AtomicReference
import mill.api.Result.catchWrapException
import mill.api.internal

@internal
private[runner] object ScalaCompilerWorker {

  @internal
  case class Resolver(
      classpath: Seq[os.Path],
      f: (mill.api.Ctx.Home, Seq[os.Path]) => Result[workerApi.ScalaCompilerWorkerApi]
  ) {
    def resolve()(using home: mill.api.Ctx.Home): Result[workerApi.ScalaCompilerWorkerApi] =
      f(home, classpath)
  }

  @internal
  case class ResolvedWorker(
      classpath: Seq[os.Path],
      worker: workerApi.ScalaCompilerWorkerApi
  ) {
    def constResolver: Resolver = Resolver(classpath, (_, _) => Result.Success(worker))
  }

  private def basicArtifact(
      org: String,
      artifact: String,
      version: String
  ): coursier.Dependency = {
    coursier.Dependency(
      coursier.Module(
        coursier.Organization(org),
        coursier.ModuleName(artifact)
      ),
      version
    )
  }

  private def bootstrapDeps: Seq[coursier.Dependency] = {
    BuildInfo.bootstrapDeps.split(";").toVector.map { dep =>
      val s"$org:$artifact:$version" = dep: @unchecked
      basicArtifact(org, artifact, version)
    }
  }

  private def bootstrapWorkerClasspath(): Result[Agg[PathRef]] = {
    val repositories = Result.create {
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.Await
      import scala.concurrent.duration.Duration
      Await.result(
        coursier.Resolve().finalRepositories.future(),
        Duration.Inf
      )
    }
    repositories.flatMap { repositories =>
      mill.util.Jvm.resolveDependencies(
        repositories = repositories,
        deps = bootstrapDeps,
        force = Nil
      ).map(_.map(_.withRevalidateOnce))
    }
  }

  private def reflectUnsafe(classpath: IterableOnce[os.Path])(using
      mill.api.Ctx.Home
  ): workerApi.ScalaCompilerWorkerApi =
    val cl = mill.api.ClassLoader.create(
      classpath.iterator.map(_.toIO.toURI.toURL).toVector,
      getClass.getClassLoader
    )
    val bridge = cl
      .loadClass("mill.runner.worker.ScalaCompilerWorkerImpl")
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[workerApi.ScalaCompilerWorkerApi]
    bridge

  private def reflectEither(classpath: IterableOnce[os.Path])(using
      mill.api.Ctx.Home
  ): Either[String, workerApi.ScalaCompilerWorkerApi] =
    catchWrapException {
      reflectUnsafe(classpath)
    }

  def reflect(classpath: IterableOnce[os.Path])(using
      mill.api.Ctx.Home
  ): Result[workerApi.ScalaCompilerWorkerApi] =
    Result.create {
      reflectUnsafe(classpath)
    }

  def bootstrapWorker(home0: os.Path): Either[String, ResolvedWorker] = {
    given mill.api.Ctx.Home = new mill.api.Ctx.Home {
      def home = home0
    }
    val classpath = bootstrapWorkerClasspath() match {
      case Result.Success(value) => Right(value)
      case Result.Failure(msg, _) => Left(msg)
      case err: Result.Exception => Left(err.toString)
      case res => Left(s"could not resolve worker classpath: $res")
    }
    classpath.flatMap { cp =>
      val resolvedCp = cp.iterator.map(_.path).toVector
      reflectEither(resolvedCp).map(worker => ResolvedWorker(resolvedCp, worker))
    }
  }
}
