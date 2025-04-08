package mill.runner.worker

import mill.PathRef
import mill.runner.worker.api.ScalaCompilerWorkerApi
import mill.api.Result

import mill.api.ExecResult.catchWrapException
import mill.api.internal

@internal
private[runner] object ScalaCompilerWorker {

  @internal
  sealed trait Resolver {
    def resolve(classpath: Seq[os.Path]): Result[ScalaCompilerWorkerApi]
  }

  @internal
  object Resolver {
    given defaultResolver: ScalaCompilerWorker.Resolver with {
      def resolve(classpath: Seq[os.Path]): Result[ScalaCompilerWorkerApi] =
        ScalaCompilerWorker.reflect(classpath)
    }
  }

  @internal
  case class ResolvedWorker(classpath: Seq[os.Path], worker: ScalaCompilerWorkerApi) {
    def constResolver: Resolver = {
      val local = worker // avoid capturing `this`
      new {
        def resolve(classpath: Seq[os.Path]): Result[ScalaCompilerWorkerApi] =
          Result.Success(local)
      }
    }
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

  private def bootstrapWorkerClasspath(): Result[Seq[PathRef]] = {
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
        force = Nil,
        checkGradleModules = false
      ).map(_.map(_.withRevalidateOnce))
    }
  }

  private def reflectUnsafe(classpath: IterableOnce[os.Path]): ScalaCompilerWorkerApi =
    val cl = mill.util.Jvm.createClassLoader(
      classpath.toVector,
      getClass.getClassLoader
    )
    val bridge = cl
      .loadClass("mill.runner.worker.ScalaCompilerWorkerImpl")
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[ScalaCompilerWorkerApi]
    bridge

  private def reflectEither(classpath: IterableOnce[os.Path])
      : Result[ScalaCompilerWorkerApi] =
    catchWrapException {
      reflectUnsafe(classpath)
    }

  def reflect(classpath: IterableOnce[os.Path]): Result[ScalaCompilerWorkerApi] =
    Result.create {
      reflectUnsafe(classpath)
    }

  def bootstrapWorker(): Result[ResolvedWorker] = {
    val classpath = bootstrapWorkerClasspath()
    classpath.flatMap { cp =>
      val resolvedCp = cp.iterator.map(_.path).toVector
      reflectEither(resolvedCp).map(worker => ResolvedWorker(resolvedCp, worker))
    }
  }
}
