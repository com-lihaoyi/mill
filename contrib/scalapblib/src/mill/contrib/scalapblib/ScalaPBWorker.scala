package mill.contrib.scalapblib

import mill.api.PathRef

import java.io.File

class ScalaPBWorker extends AutoCloseable {

  private def scalaPB(scalaPBClasspath: Seq[PathRef])(using ctx: mill.api.TaskCtx) = {
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
            val opts = if (scalaPBOptions.isEmpty || !gen.supportsScalaPbOptions) ""
            else scalaPBOptions + ":"
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
   * @oaram generators At least on generators to use. See [[Generator]] for available generators.
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
  )(using ctx: mill.api.TaskCtx): mill.api.Result[PathRef] = {
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
    compiler.compileScalaPB(
      roots,
      sources,
      scalaPBOptions,
      dest.toIO,
      scalaPBCExtraArgs,
      generators
    )
    mill.api.Result.Success(PathRef(dest))
  }

  override def close(): Unit = {
    // no-op
  }
}
