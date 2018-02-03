package mill
package twirllib

import java.io.File
import java.net.URLClassLoader
import ammonite.ops.{Path,ls}


class TwirlWorker  {

  private var twirlInstanceCache = Option.empty[(Long, TwirlWorkerApi)]

  private def twirl(twirlClasspath: Agg[Path]) = {
    val classloaderSig =
      twirlClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    twirlInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray)
        val clt = cl.loadClass("play.twirl.compiler.TwirlCompiler")
        val instance = clt.getDeclaredConstructor().newInstance()
          .asInstanceOf[TwirlWorkerApi]
        twirlInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }
  
  def compile(twirlClasspath: Agg[Path],
              sourceDirectories: Seq[Path],
              dest: Path) : Unit = {
    val compiler = twirl(twirlClasspath)
    def compileTwirlDir(inputDir: Path) = {
      val twirlFiles = ls.rec(inputDir).filter(_.name.matches(".*.scala.(html|xml|js|txt)"))
      twirlFiles.map { tFile =>
        val extFormat = twirlExtensionFormat(tFile.name)
        compiler.compile( tFile.toIO,
                          inputDir.toIO,
                          dest.toIO,
                          s"play.twirl.api.${extFormat}"
        )
      }
    }
    sourceDirectories.map(compileTwirlDir)
  }
 
 private def twirlExtensionFormat(name: String) =
        if (name.endsWith("html"))     "HtmlFormat"
        else if (name.endsWith("xml")) "XmlFormat"
        else if (name.endsWith("js"))  "JavaScriptFormat"
        else "TxtFormat"

}

trait TwirlWorkerApi {
  def compile(source: File,
              sourceDirectory: File,
              generatedDirectory: File,
              formatterType: String,
              additionalImports: Seq[String] = Nil,
              constructorAnnotations: Seq[String] = Nil,
  //            codec: Codec = TwirlIO.defaultCodec,
              inclusiveDot: Boolean = false): Unit   
}

object TwirlWorkerApi extends mill.define.BaseModule(ammonite.ops.pwd) {

  def twirlWorker = T.worker { new TwirlWorker() }
}
