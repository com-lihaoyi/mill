package mill
package twirllib

import java.io.File
import java.net.URLClassLoader
import java.{lang, util}

import ammonite.ops.{Path, ls}
import mill.eval.PathRef
import mill.scalalib.CompilationResult

import scala.io.Codec
import scala.reflect.runtime.universe._

class TwirlWorker {

  private var twirlInstanceCache = Option.empty[(Long, TwirlWorkerApi)]
  import scala.collection.JavaConverters._

  private def twirl(twirlClasspath: Agg[Path]) = {
    val classloaderSig = twirlClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    twirlInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray)
        val twirlCompilerClass = cl.loadClass("play.japi.twirl.compiler.TwirlCompiler")
        // Use the Java API (available in Twirl 1.3+)
        // Using reflection on a method with "Seq[String] = Nil" parameter type does not seem to work.

        // REMIND: Unable to call the compile method with a primitive boolean
        // codec and inclusiveDot will not be available
        val compileMethod = twirlCompilerClass.getMethod("compile",
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.lang.String],
          classOf[util.Collection[java.lang.String]],
          classOf[util.List[java.lang.String]])

        val instance = new TwirlWorkerApi {
          override def compileTwirl(source: File,
                                    sourceDirectory: File,
                                    generatedDirectory: File,
                                    formatterType: String,
                                    additionalImports: Seq[String] = Nil,
                                    constructorAnnotations: Seq[String] = Nil) {
            val o = compileMethod.invoke(null, source,
              sourceDirectory,
              generatedDirectory,
              formatterType,
              additionalImports.asJava,
              constructorAnnotations.asJava)
          }
        }
        twirlInstanceCache = Some((classloaderSig, instance))
        instance
    }
  }

  def compile(twirlClasspath: Agg[Path], sourceDirectories: Seq[Path], dest: Path)
             (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compiler = twirl(twirlClasspath)

    def compileTwirlDir(inputDir: Path) {
      ls.rec(inputDir).filter(_.name.matches(".*.scala.(html|xml|js|txt)"))
        .foreach { template =>
          val extFormat = twirlExtensionFormat(template.name)
          compiler.compileTwirl(template.toIO,
            inputDir.toIO,
            dest.toIO,
            s"play.twirl.api.$extFormat"
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
                   additionalImports: Seq[String] = Nil,
                   constructorAnnotations: Seq[String] = Nil)
}

object TwirlWorkerApi {

  def twirlWorker = new TwirlWorker()
}
