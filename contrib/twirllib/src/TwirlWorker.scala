package mill
package twirllib

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.charset.Charset

import mill.api.PathRef
import mill.scalalib.api.CompilationResult

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
        // * we'll need to construct a collection of additional imports (will need to also include the defaults and add the user-provided additional imports)
        // * we'll need to construct a collection of constructor annotations// *
        // * the default collection in scala api is a Seq[String]
        // * but it is defined in a different classloader (namely in cl)
        // * so we can not construct our own Seq and pass it to the method - it will be from our classloader, and not compatible
        // * the java api uses java collections, manipulating which using reflection is much simpler
        //
        // NOTE: When creating the cl classloader with passing the current classloader as the parent:
        //   val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray, getClass.getClassLoader)
        // it is possible to cast the default to a Seq[String], construct our own Seq[String], and pass it to the method invoke -
        // classe will be compatible (the tests passed).
        // But when run in an actual mill project with this module enabled, there were exceptions like this:
        // scala.reflect.internal.MissingRequirementError: object scala in compiler mirror not found.

        val twirlCompilerClass = cl.loadClass("play.japi.twirl.compiler.TwirlCompiler")

        val codecClass = cl.loadClass("scala.io.Codec")
        val charsetClass = cl.loadClass("java.nio.charset.Charset")
        val arrayListClass = cl.loadClass("java.util.ArrayList")
        val hashSetClass = cl.loadClass("java.util.HashSet")

        val codecApplyMethod = codecClass.getMethod("apply", charsetClass)
        val charsetForNameMethod = charsetClass.getMethod("forName", classOf[java.lang.String])

        val compileMethod = twirlCompilerClass.getMethod("compile",
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.lang.String],
          cl.loadClass("java.util.Collection"),
          cl.loadClass("java.util.List"),
          cl.loadClass("scala.io.Codec"),
          classOf[Boolean])

        val defaultImportsMethod = twirlCompilerClass.getField("DEFAULT_IMPORTS")

        val hashSetConstructor = hashSetClass.getConstructor(cl.loadClass("java.util.Collection"))

        val instance = new TwirlWorkerApi {
          override def compileTwirl(source: File,
                                    sourceDirectory: File,
                                    generatedDirectory: File,
                                    formatterType: String,
                                    additionalImports: Seq[String],
                                    constructorAnnotations: Seq[String],
                                    codec: Codec,
                                    inclusiveDot: Boolean) {
            // val defaultImports = play.japi.twirl.compiler.TwirlCompiler.DEFAULT_IMPORTS()
            // val twirlAdditionalImports = new HashSet(defaultImports)
            // additionalImports.foreach(twirlAdditionalImports.add)
            val defaultImports = defaultImportsMethod.get(null) // unmodifiable collection
            val twirlAdditionalImports = hashSetConstructor.newInstance(defaultImports).asInstanceOf[Object]
            val hashSetAddMethod = twirlAdditionalImports.getClass.getMethod("add", classOf[Object])
            additionalImports.foreach(hashSetAddMethod.invoke(twirlAdditionalImports, _))

            // Codec.apply(Charset.forName(codec.charSet.name()))
            val twirlCodec = codecApplyMethod.invoke(null, charsetForNameMethod.invoke(null, codec.charSet.name()))

            // val twirlConstructorAnnotations = new ArrayList()
            // constructorAnnotations.foreach(twirlConstructorAnnotations.add)
            val twirlConstructorAnnotations = arrayListClass.newInstance().asInstanceOf[Object]
            val arrayListAddMethod = twirlConstructorAnnotations.getClass.getMethod("add", classOf[Object])
            constructorAnnotations.foreach(arrayListAddMethod.invoke(twirlConstructorAnnotations, _))

            // JavaAPI
            //   public static Optional<File> compile(
            //   File source,
            //   File sourceDirectory,
            //   File generatedDirectory,
            //   String formatterType,
            //   Collection<String> additionalImports,
            //   List<String> constructorAnnotations,
            //   Codec codec,
            //   boolean inclusiveDot
            // )
            val o = compileMethod.invoke(null, source,
              sourceDirectory,
              generatedDirectory,
              formatterType,
              twirlAdditionalImports,
              twirlConstructorAnnotations,
              twirlCodec,
              Boolean.box(inclusiveDot)
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
             (implicit ctx: mill.api.Ctx): mill.api.Result[CompilationResult] = {
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

    mill.api.Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
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
