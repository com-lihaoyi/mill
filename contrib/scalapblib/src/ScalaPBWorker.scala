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
          override def compileScalaPB(source: File, scalaPBOptions: String, generatedDirectory: File, includes: Seq[os.Path]) {
            val opts = if (scalaPBOptions.isEmpty) "" else scalaPBOptions + ":"
            val args = protocPath.map(path => s"--protoc=$path").toSeq ++ Seq(
              "--throw",
              s"--scala_out=${opts}${generatedDirectory.getCanonicalPath}",
              s"--proto_path=${source.getParentFile.getCanonicalPath}"
            ) ++
              includes.map(i => s"--proto_path=${i.toIO.getCanonicalPath}") :+
              source.getCanonicalPath
            mainMethod.invoke(null, args.toArray)
          }
        }
        scalaPBInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }

  def compile(scalaPBClasspath: Agg[os.Path], protocPath: Option[String], scalaPBSources: Seq[os.Path], scalaPBOptions: String, dest: os.Path, scalaPBIncludePath: Seq[os.Path])
             (implicit ctx: mill.api.Ctx): mill.api.Result[PathRef] = {
    val compiler = scalaPB(scalaPBClasspath, protocPath)

    def compileScalaPBDir(inputDir: os.Path) {
      // ls throws if the path doesn't exist
      if (inputDir.toIO.exists) {
        os.walk(inputDir).filter(_.last.matches(".*.proto"))
          .foreach { proto =>
            compiler.compileScalaPB(proto.toIO, scalaPBOptions, dest.toIO, scalaPBIncludePath)
          }
      }
    }

    scalaPBSources.foreach(compileScalaPBDir)

    mill.api.Result.Success(PathRef(dest))
  }
}

trait ScalaPBWorkerApi {
  def compileScalaPB(source: File, scalaPBOptions: String, generatedDirectory: File, includes: Seq[os.Path])
}

object ScalaPBWorkerApi {

  def scalaPBWorker = new ScalaPBWorker()
}
