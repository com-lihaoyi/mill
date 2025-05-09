package mill.javalib.spotless

import coursier.parse.DependencyParser
import mill.api.Result
import mill.constants.OutFiles
import mill.define.*
import mill.javalib.Dep
import mill.scalalib.{CoursierModule, JavaModule}
import mill.util.Jvm
import mill.util.TokenReaders.given

import java.net.URLClassLoader
import scala.collection.mutable

class SpotlessWorker(cl: ClassLoader) extends AutoCloseable {
  private var configSig: Int = 0
  private val reformattedSig: mutable.Map[os.Path, Int] = mutable.Map.empty
  private val onReformat: os.Path => Unit = path => reformattedSig.update(path, PathRef(path).sig)
  private def isChanged(ref: PathRef) = !reformattedSig.get(ref.path).contains(ref.sig)

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
      val instance = api(spotlessConfig, configRoots, resolver)
      val method = instance.getClass.getMethods.find(_.getName == "format").get
      val status = method.invoke(instance, toConsider, check, ctx.workspace).asInstanceOf[Int]
      val prefix = if check then "format check" else "format"
      if (0 == status) ctx.log.info(s"$prefix completed")
      else ctx.fail(s"$prefix failed in $status files")
    } else ctx.log.info("everything is already formatted")
  }

  def prepare(
      spotlessConfig: PathRef,
      configRoots: Seq[PathRef],
      resolver: CoursierModule.Resolver
  )(using TaskCtx): Result[Unit] = {
    api(spotlessConfig, configRoots, resolver)
  }

  private def api(
      spotlessConfig: PathRef,
      configRoots: Seq[PathRef],
      resolver: CoursierModule.Resolver
  )(using TaskCtx): AnyRef = {
    val provision = (_: Boolean, mavenCoordinates: Seq[String]) =>
      resolver.artifacts(
        DependencyParser.dependencies(mavenCoordinates, "")
          .either.fold(errs => throw Exception(errs.mkString(", ")), identity)
      ).files
    val cls = cl.loadClass("mill.spotless.SpotlessApi")
    val method = cls.getMethods.find(_.getName == "prepare").get
    method.invoke(null, spotlessConfig.path, configRoots.map(_.path), provision, onReformat)
  }

  def close() = {
    configSig = 0
    reformattedSig.clear()
  }
}

object SpotlessWorkerModule extends ExternalModule with JavaModule {

  def spotlessClasspath: Task[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-spotless")))
  }

  def spotlessClassloader: Worker[URLClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      spotlessClasspath().map(_.path),
      // prefixes required for creating and running mill.spotless.SpotlessApi using reflection
      sharedPrefixes = Seq("os", "scala")
    )
  }

  def worker: Worker[SpotlessWorker] = Task.Worker {
    new SpotlessWorker(spotlessClassloader())
  }

  lazy val millDiscover = Discover[this.type]
}
