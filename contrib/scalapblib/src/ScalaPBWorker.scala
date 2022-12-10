package mill
package contrib.scalapblib

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

import mill.api.PathRef
import mill.T
import mill.define.{Discover, ExternalModule, Worker}

class ScalaPBWorker extends AutoCloseable {

  private var scalaPBInstanceCache = Option.empty[(Long, ScalaPBWorkerApi)]

  private def scalaPB(scalaPBClasspath: Agg[PathRef])(implicit ctx: mill.api.Ctx) = {
    val classloaderSig = scalaPBClasspath.hashCode
    scalaPBInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val pbcClasspath = scalaPBClasspath.map(_.path.toIO.toURI.toURL).toVector
        val cl = mill.api.ClassLoader.create(pbcClasspath, null)
        val scalaPBCompilerClass = cl.loadClass("scalapb.ScalaPBC")
        val mainMethod = scalaPBCompilerClass.getMethod("main", classOf[Array[java.lang.String]])

        val instance = new ScalaPBWorkerApi {
          override def compileScalaPB(
              roots: Seq[File],
              sources: Seq[File],
              scalaPBOptions: String,
              generatedDirectory: File,
              otherArgs: Seq[String]
          ): Unit = {
            val opts = if (scalaPBOptions.isEmpty) "" else scalaPBOptions + ":"
            val args = otherArgs ++ Seq(
              s"--scala_out=${opts}${generatedDirectory.getCanonicalPath}"
            ) ++ roots.map(root => s"--proto_path=${root.getCanonicalPath}") ++ sources.map(
              _.getCanonicalPath
            )
            ctx.log.debug(s"ScalaPBC args: ${args.mkString(" ")}")
            mainMethod.invoke(null, args.toArray)
          }
        }
        scalaPBInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }

  /**
   * Build arguments for ScalaPBC, except scala_out/proto_path for source/source
   *
   * @param protocPath optional protoc path.
   * @param includes proto paths other than source proto.
   * @param additionalArgs other arguments.
   *
   * @return arguments for ScalaPBC
   */
  def compileOptions(
      protocPath: Option[String],
      includes: Seq[os.Path],
      additionalArgs: Seq[String]
  ): Seq[String] = {
    protocPath.map(path => s"--protoc=$path").toSeq ++
      Seq("--throw") ++ additionalArgs ++
      includes.map(i => s"--proto_path=${i.toIO.getCanonicalPath}")
  }

  /**
   * compile protobuf using ScalaPBC
   *
   * @param scalaPBClasspath classpaths for ScalaPBC to run
   * @param scalaPBSources proto files to be compiles
   * @param scalaPBOptions option string specific for scala generator. (the options in `--scala_out=<options>:output_path`)
   * @param dest output path
   * @param scalaPBCExtraArgs extra arguments other than `--scala_out=<options>:output_path`, `--proto_path=source_parent`, `source`
   *
   * @return execute result with path ref to `dest`
   */
  def compile(
      scalaPBClasspath: Agg[PathRef],
      scalaPBSources: Seq[os.Path],
      scalaPBOptions: String,
      dest: os.Path,
      scalaPBCExtraArgs: Seq[String]
  )(implicit ctx: mill.api.Ctx): mill.api.Result[PathRef] = {
    val compiler = scalaPB(scalaPBClasspath)
    val sources = scalaPBSources.flatMap {
      path =>
        val ioFile = path.toIO
        // ls throws if the path doesn't exist
        if (ioFile.exists() && ioFile.isDirectory)
          os
            .walk(path)
            .filter(_.last.matches(".*.proto"))
            .map(_.toIO)
        else
          Seq(ioFile)
    }
    val roots = scalaPBSources.map(_.toIO).filter(_.isDirectory)
    compiler.compileScalaPB(roots, sources, scalaPBOptions, dest.toIO, scalaPBCExtraArgs)
    mill.api.Result.Success(PathRef(dest))
  }

  override def close(): Unit = {
    scalaPBInstanceCache = None
  }
}

trait ScalaPBWorkerApi {

  @deprecated("Use other overload instead", "Mill after 0.10.9")
  def compileScalaPB(
      root: File,
      source: Seq[File],
      scalaPBOptions: String,
      generatedDirectory: File,
      otherArgs: Seq[String]
  ): Unit =
    compileScalaPB(Seq(root), source, scalaPBOptions, generatedDirectory, otherArgs)

  def compileScalaPB(
      roots: Seq[File],
      source: Seq[File],
      scalaPBOptions: String,
      generatedDirectory: File,
      otherArgs: Seq[String]
  ): Unit
}

object ScalaPBWorkerApi extends ExternalModule {
  def scalaPBWorker: Worker[ScalaPBWorker] = T.worker { new ScalaPBWorker() }
  lazy val millDiscover = Discover[this.type]
}
