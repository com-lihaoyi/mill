package mill
package twirllib

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

import mill.eval.PathRef
import mill.scalalib.CompilationResult

import scala.io.Codec

class TwirlWorker {

  private var twirlInstanceCache = Option.empty[(Long, TwirlWorkerApi)]

  private def twirl(twirlClasspath: Agg[os.Path]) = {
    val classloaderSig = twirlClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    twirlInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray, null)
        val twirlCompilerClass = cl.loadClass("play.twirl.compiler.TwirlCompiler")
        val compileMethod = twirlCompilerClass.getMethod("compile",
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.lang.String],
          cl.loadClass("scala.collection.Seq"),
          cl.loadClass("scala.collection.Seq"),
          cl.loadClass("scala.io.Codec"),
          classOf[Boolean])

        val defaultAdditionalImportsMethod = twirlCompilerClass.getMethod("compile$default$5")
        val defaultConstructorAnnotationsMethod = twirlCompilerClass.getMethod("compile$default$6")
        val defaultCodecMethod = twirlCompilerClass.getMethod("compile$default$7")
        val defaultFlagMethod = twirlCompilerClass.getMethod("compile$default$8")

        val instance = new TwirlWorkerApi {
          override def compileTwirl(source: File,
                                    sourceDirectory: File,
                                    generatedDirectory: File,
                                    formatterType: String,
                                    additionalImports: Seq[String],
                                    constructorAnnotations: Seq[String],
                                    codec: Codec,
                                    inclusiveDot: Boolean) {
            val o = compileMethod.invoke(null, source,
              sourceDirectory,
              generatedDirectory,
              formatterType,
              defaultAdditionalImportsMethod.invoke(null),
              defaultConstructorAnnotationsMethod.invoke(null),
              defaultCodecMethod.invoke(null),
              defaultFlagMethod.invoke(null))
          }
        }
        twirlInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }

  def compile(twirlClasspath: Agg[os.Path],
              sourceDirectories: Seq[os.Path],
              dest: os.Path,
              additionalImports: Seq[String],
              constructorAnnotations: Seq[String],
              codec: Codec,
              inclusiveDot: Boolean)
             (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compiler = twirl(twirlClasspath)

    def compileTwirlDir(inputDir: os.Path) {
      os.walk(inputDir).filter(_.last.matches(".*.scala.(html|xml|js|txt)"))
        .foreach { template =>
          val extFormat = twirlExtensionFormat(template.last)
          compiler.compileTwirl(template.toIO,
            inputDir.toIO,
            dest.toIO,
            s"play.twirl.api.$extFormat",
            additionalImports,
            constructorAnnotations,
            codec,
            inclusiveDot
          )
        }
    }

    sourceDirectories.foreach(compileTwirlDir)

    val zincFile = ctx.dest / 'zinc
    val classesDir = ctx.dest / 'html

    mill.eval.Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
  }

  private def twirlExtensionFormat(name: String) =
    if (name.endsWith("html")) "HtmlFormat"
    else if (name.endsWith("xml")) "XmlFormat"
    else if (name.endsWith("js")) "JavaScriptFormat"
    else "TxtFormat"
}

trait TwirlWorkerApi {
  def compileTwirl(source: File,
                   sourceDirectory: File,
                   generatedDirectory: File,
                   formatterType: String,
                   additionalImports: Seq[String],
                   constructorAnnotations: Seq[String],
                   codec: Codec,
                   inclusiveDot: Boolean)
}

object TwirlWorkerApi {

  def twirlWorker = new TwirlWorker()
}
