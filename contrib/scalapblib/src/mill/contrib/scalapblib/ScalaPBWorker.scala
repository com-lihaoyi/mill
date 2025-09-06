package mill
package contrib.scalapblib

import java.io.File

import mill.api.PathRef
import mill.api.{Discover, ExternalModule}
import upickle.default.ReadWriter

class ScalaPBWorker {

  private def scalaPB(scalaPBClasspath: Seq[PathRef])(implicit ctx: mill.api.TaskCtx) = {
    val instance = new ScalaPBWorkerApi {
      override def compileScalaPB(
          roots: Seq[File],
          sources: Seq[File],
          scalaPBOptions: String,
          generatedDirectory: File,
          otherArgs: Seq[String],
          generators: Seq[Generator]
      ): Unit = {
        val pbcClasspath = scalaPBClasspath.map(_.path).toVector
        mill.util.Jvm.withClassLoader(pbcClasspath, null) { cl =>
          val scalaPBCompilerClass = cl.loadClass("scalapb.ScalaPBC")
          val mainMethod = scalaPBCompilerClass.getMethod("main", classOf[Array[java.lang.String]])
          val args = otherArgs ++ generators.map { gen =>
            val opts = if (scalaPBOptions.isEmpty || !gen.supportsScalaPBOptions) "" else scalaPBOptions + ":"
            s"${gen.generator}=$opts${generatedDirectory.getCanonicalPath}"
          } ++ roots.map(root => s"--proto_path=${root.getCanonicalPath}") ++ sources.map(
            _.getCanonicalPath
          )
          ctx.log.debug(s"ScalaPBC args: ${args.mkString(" ")}")
          mainMethod.invoke(null, args.toArray)
        }
      }
    }
    instance
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
      scalaPBClasspath: Seq[PathRef],
      scalaPBSources: Seq[os.Path],
      scalaPBOptions: String,
      dest: os.Path,
      scalaPBCExtraArgs: Seq[String],
      generators: Seq[Generator]
  )(implicit ctx: mill.api.TaskCtx): mill.api.Result[PathRef] = {
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
    compiler.compileScalaPB(roots, sources, scalaPBOptions, dest.toIO, scalaPBCExtraArgs, generators)
    mill.api.Result.Success(PathRef(dest))
  }
}

trait ScalaPBWorkerApi {
  def compileScalaPB(
      roots: Seq[File],
      source: Seq[File],
      scalaPBOptions: String,
      generatedDirectory: File,
      otherArgs: Seq[String],
      generators: Seq[Generator]
  ): Unit
}

sealed trait Generator derives ReadWriter {
  def generator: String
  def supportsScalaPBOptions: Boolean
}
case object ScalaGen extends Generator {
  override def generator: String = "--scala_out"
  override def supportsScalaPBOptions: Boolean = true
}
case object JavaGen extends Generator {
  override def generator: String = "--java_out"
  override def supportsScalaPBOptions: Boolean = false // Java options are specified directly in the proto file
}

object ScalaPBWorkerApi extends ExternalModule {
  def scalaPBWorker: Worker[ScalaPBWorker] = Task.Worker { new ScalaPBWorker() }
  lazy val millDiscover = Discover[this.type]
}
