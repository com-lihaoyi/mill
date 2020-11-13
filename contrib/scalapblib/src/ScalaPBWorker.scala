package mill
package contrib.scalapblib

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

import mill.api.PathRef

class ScalaPBWorker {

  private var scalaPBInstanceCache = Option.empty[(Long, ScalaPBWorkerApi)]

  private def scalaPB(scalaPBClasspath: Agg[os.Path], protocPath: Option[String]) = {
    val classloaderSig = scalaPBClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scalaPBInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(scalaPBClasspath.map(_.toIO.toURI.toURL).toArray)
        val scalaPBCompilerClass = cl.loadClass("scalapb.ScalaPBC")
        val mainMethod = scalaPBCompilerClass.getMethod("main", classOf[Array[java.lang.String]])

        val instance = new ScalaPBWorkerApi {
          override def compileScalaPB(args: Seq[String]) {
            mainMethod.invoke(null, args.toArray)
          }
        }
        scalaPBInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }

  /** Builds the compilation arguments for scalaPBC:
   *
   *   - 1st seq to encapsulate the sources.
   *   - 2nd seq for proto files.
   *   - 3rd seq for the scalaPBC args.
   */
  def compilationArgs(
    protocPath: Option[String],
    scalaPBSources: Seq[os.Path],
    scalaPBOptions: String,
    generatedDirectory: os.Path,
    includes: Seq[os.Path],
    customArgs: Seq[String]
  ): Seq[Seq[Seq[String]]] = 
    // ls throws if the path doesn't exist
    scalaPBSources.filter(_.toIO.exists).map { inputDir: os.Path =>
      os.walk(inputDir).filter(_.last.matches(".*.proto")).map { proto =>
        val source = proto.toIO
        val opts = if (scalaPBOptions.isEmpty) "" else scalaPBOptions + ":"
        protocPath.map(path => s"--protoc=$path").toSeq ++ Seq(
          "--throw",
          s"--scala_out=${opts}${generatedDirectory.toIO.getCanonicalPath}",
          s"--proto_path=${source.getParentFile.getCanonicalPath}"
        ) ++ customArgs ++
          includes.map(i => s"--proto_path=${i.toIO.getCanonicalPath}") :+
          source.getCanonicalPath
      }
    }

  def compile(
    scalaPBClasspath: Agg[os.Path],
    protocPath: Option[String],
    dest: os.Path,
    args: Seq[Seq[Seq[String]]]
  )(implicit ctx: mill.api.Ctx): mill.api.Result[PathRef] = {
    val compiler = scalaPB(scalaPBClasspath, protocPath)

    args.foreach(_.foreach(compiler.compileScalaPB))

    mill.api.Result.Success(PathRef(dest))
  }
}

trait ScalaPBWorkerApi {
  def compileScalaPB(args: Seq[String])
}

object ScalaPBWorkerApi {

  def scalaPBWorker = new ScalaPBWorker()
}
