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

        // Switched to using the java api because of the hack-ish thing going on later.
        //
        // * we'll need to construct a collection of additional imports
        // * it will need to consider the defaults
        // * and add the user-provided additional imports
        // * the default collection in scala api is a Seq[String]
        // * but it is defined in a different classloader (namely in cl)
        // * so we can not construct our own Seq and pass it to the method - it will be from our classloader, and not compatible
        // * the java api has a Collection as the type for this param, for which it is much more doable to append things to it using reflection
        //
        // NOTE: I tried creating the cl classloader passing the current classloader as the parent:
        //   val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray, getClass.getClassLoader)
        // in that case it was possible to cast the default to a Seq[String], construct our own Seq[String], and pass it to the method invoke- it was compatible.
        // And the tests passed. But when run in a different mill project, I was getting exceptions like this:
        // scala.reflect.internal.MissingRequirementError: object scala in compiler mirror not found.

        val twirlCompilerClass = cl.loadClass("play.japi.twirl.compiler.TwirlCompiler")

        // this one is only to get the codec: Codec parameter default value
        val twirlScalaCompilerClass = cl.loadClass("play.twirl.compiler.TwirlCompiler")

        val compileMethod = twirlCompilerClass.getMethod("compile",
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.lang.String],
          cl.loadClass("java.util.Collection"),
          cl.loadClass("java.util.List"),
          cl.loadClass("scala.io.Codec"),
          classOf[Boolean])

        val arrayListClass = cl.loadClass("java.util.ArrayList")
        val hashSetClass = cl.loadClass("java.util.HashSet")

        val defaultAdditionalImportsMethod = twirlCompilerClass.getField("DEFAULT_IMPORTS")
        val defaultCodecMethod = twirlScalaCompilerClass.getMethod("compile$default$7")

        val instance = new TwirlWorkerApi {
          override def compileTwirl(source: File,
                                    sourceDirectory: File,
                                    generatedDirectory: File,
                                    formatterType: String,
                                    additionalImports: Seq[String],
                                    constructorAnnotations: Seq[String],
                                    codec: Codec,
                                    inclusiveDot: Boolean) {
            val defaultAdditionalImports = defaultAdditionalImportsMethod.get(null) // unmodifiable collection
            // copying it into a modifiable hash set and adding all additional imports
            val allAdditionalImports =
              hashSetClass
                .getConstructor(cl.loadClass("java.util.Collection"))
                .newInstance(defaultAdditionalImports)
                .asInstanceOf[Object]
            val hashSetAddMethod =
              allAdditionalImports
                .getClass
                .getMethod("add", classOf[Object])
            additionalImports.foreach(hashSetAddMethod.invoke(allAdditionalImports, _))

            val o = compileMethod.invoke(null, source,
              sourceDirectory,
              generatedDirectory,
              formatterType,
              allAdditionalImports,
              arrayListClass.newInstance().asInstanceOf[Object], // empty list seems to be the default
              defaultCodecMethod.invoke(null),
              Boolean.box(false)
            )
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
    val classesDir = ctx.dest

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
