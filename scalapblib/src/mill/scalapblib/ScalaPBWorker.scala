package mill
package scalapblib

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

import ammonite.ops.{Path, ls}
import mill.eval.PathRef

class ScalaPBWorker {

  private var scalaPBInstanceCache = Option.empty[(Long, ScalaPBWorkerApi)]

  private def scalaPB(scalaPBClasspath: Agg[Path]) = {
    val classloaderSig = scalaPBClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    scalaPBInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(scalaPBClasspath.map(_.toIO.toURI.toURL).toArray)
        val scalaPBCompilerClass = cl.loadClass("scalapb.ScalaPBC")
        val mainMethod = scalaPBCompilerClass.getMethod("main", classOf[Array[java.lang.String]])

        val instance = new ScalaPBWorkerApi {
          override def compileScalaPB(source: File, scalaPBOptions: String, generatedDirectory: File) {
            val opts = if (scalaPBOptions.isEmpty) "" else scalaPBOptions + ":"
            mainMethod.invoke(
              null,
              Array(
                "--throw",
                s"--scala_out=${opts}${generatedDirectory.getCanonicalPath}",
                s"--proto_path=${source.getParentFile.getCanonicalPath}",
                source.getCanonicalPath
              )
            )
          }
        }
        scalaPBInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }

  def compile(scalaPBClasspath: Agg[Path], scalaPBSources: Seq[Path], scalaPBOptions: String, dest: Path)
             (implicit ctx: mill.util.Ctx): mill.eval.Result[PathRef] = {
    val compiler = scalaPB(scalaPBClasspath)

    def compileScalaPBDir(inputDir: Path) {
      // ls throws if the path doesn't exist
      if (inputDir.toIO.exists) {
        ls.rec(inputDir).filter(_.name.matches(".*.proto"))
          .foreach { proto =>
            compiler.compileScalaPB(proto.toIO, scalaPBOptions, dest.toIO)
          }
      }
    }

    scalaPBSources.foreach(compileScalaPBDir)

    mill.eval.Result.Success(PathRef(dest))
  }
}

trait ScalaPBWorkerApi {
  def compileScalaPB(source: File, scalaPBOptions: String, generatedDirectory: File)
}

object ScalaPBWorkerApi {

  def scalaPBWorker = new ScalaPBWorker()
}
