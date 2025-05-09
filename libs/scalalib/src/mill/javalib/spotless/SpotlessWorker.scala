package mill.javalib.spotless

import coursier.parse.DependencyParser
import mill.api.Result
import mill.constants.OutFiles
import mill.define.*
import mill.javalib.Dep
import mill.scalalib.{CoursierModule, JavaModule}
import mill.util.Jvm
import mill.util.TokenReaders.given

import java.io.File
import java.net.URLClassLoader
import scala.collection.mutable

@mill.api.experimental
class SpotlessWorker(cl: ClassLoader) extends AutoCloseable {
  private var configSig: Int = 0
  private val formattedSig: mutable.Map[os.Path, Int] = mutable.Map.empty
  private def isChanged(ref: PathRef) = !formattedSig.get(ref.path).contains(ref.sig)

  def close() = {
    configSig = 0
    formattedSig.clear()
  }

  def format(
      sources: Seq[PathRef],
      check: Boolean,
      spotlessConfig: PathRef,
      configRoots: Seq[PathRef],
      resolver: CoursierModule.Resolver
  )(using ctx: TaskCtx): Result[Unit] = {
    val skipped = Task.workspace / OutFiles.out
    val files = sources.flatMap { ref =>
      val path = ref.path
      if os.isDir(path) then
        os.walk.stream(path, skip = skipped.equals)
          .collect:
            case p if os.isFile(p) => PathRef(p)
          .toSeq
      else if os.isFile(path) then Seq(ref)
      else Seq()
    }
    val toConsider =
      if spotlessConfig.sig == configSig then files.map(_.path)
      else files.collect { case ref if isChanged(ref) => ref.path }
    if (toConsider.nonEmpty) {
      val api = spotlessApiClass.getMethod(
        "create",
        classOf[os.Path],
        classOf[Seq[os.Path]],
        classOf[Provision]
      ).invoke(null, spotlessConfig.path, configRoots.map(_.path), provision(resolver))
      val failedFileCount = api.getClass.getMethod(
        "format",
        classOf[os.Path],
        classOf[Seq[os.Path]],
        classOf[Boolean],
        classOf[OnFormat]
      ).invoke(api, ctx.workspace, toConsider, check, onFormat).asInstanceOf[Int]
      val prefix = if check then "format check" else "format"
      if (failedFileCount == 0) ctx.log.info(s"$prefix completed")
      else ctx.fail(s"$prefix failed in $failedFileCount files")
    } else ctx.log.info("everything is already formatted")
  }

  def prepareOffline(
      spotlessConfig: PathRef,
      configRoots: Seq[PathRef],
      resolver: CoursierModule.Resolver
  )(using ctx: TaskCtx): Seq[PathRef] = {
    spotlessApiClass
      .getMethod("prepareOffline", classOf[os.Path], classOf[Seq[os.Path]], classOf[Provision])
      .invoke(null, spotlessConfig.path, configRoots.map(_.path), provision(resolver))
      .asInstanceOf[Seq[File]]
      .map(file => PathRef(os.Path(file)))
  }

  private def spotlessApiClass = cl.loadClass("mill.spotless.SpotlessApi")

  private type OnFormat = os.Path => Unit
  private val onFormat: OnFormat = path => formattedSig.update(path, PathRef(path).sig)

  private type Provision = (Boolean, Seq[String]) => Set[File]
  private def provision(resolver: CoursierModule.Resolver)(using TaskCtx): Provision =
    (_, mavenCoordinates) => {
      val deps = DependencyParser.dependencies(mavenCoordinates, "")
        .either.fold(errs => throw Exception(errs.mkString(", ")), identity)
      resolver.artifacts(deps).files.toSet
    }
}

@mill.api.experimental
object SpotlessWorkerModule extends ExternalModule with JavaModule {

  def spotlessClasspath: Task[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-spotless")))
  }

  def spotlessClassloader: Worker[URLClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      spotlessClasspath().map(_.path),
      // required for creating and running mill.spotless.SpotlessApi using reflection
      sharedPrefixes = Seq("os", "scala")
    )
  }

  def worker: Worker[SpotlessWorker] = Task.Worker {
    new SpotlessWorker(spotlessClassloader())
  }

  lazy val millDiscover = Discover[this.type]
}
