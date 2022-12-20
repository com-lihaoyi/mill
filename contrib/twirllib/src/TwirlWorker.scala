package mill
package twirllib

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.charset.Charset

import mill.api.PathRef
import mill.scalalib.api.CompilationResult

import scala.jdk.CollectionConverters._
import scala.io.Codec
import scala.util.matching.Regex

class TwirlWorker {

  private var twirlInstanceCache = Option.empty[(Int, (TwirlWorkerApi, Class[_]))]

  private def twirlCompilerAndClass(twirlClasspath: Agg[PathRef]): (TwirlWorkerApi, Class[_]) = {
    val classloaderSig = twirlClasspath.hashCode
    twirlInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(twirlClasspath.map(_.path.toIO.toURI.toURL).toArray, null)

        // Switched to using the java api because of the hack-ish thing going on later.
        //
        // * we'll need to construct a collection of imports
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

        val compileMethod = twirlCompilerClass.getMethod(
          "compile",
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.io.File],
          classOf[java.lang.String],
          cl.loadClass("java.util.Collection"),
          cl.loadClass("java.util.List"),
          cl.loadClass("scala.io.Codec"),
          classOf[Boolean]
        )

        val instance = new TwirlWorkerApi {
          override def compileTwirl(
              source: File,
              sourceDirectory: File,
              generatedDirectory: File,
              formatterType: String,
              imports: Seq[String],
              constructorAnnotations: Seq[String],
              codec: Codec,
              inclusiveDot: Boolean
          ): Unit = {
            // val twirlImports = new HashSet()
            // imports.foreach(twirlImports.add)
            val twirlImports = hashSetClass.newInstance().asInstanceOf[Object]
            val hashSetAddMethod = twirlImports.getClass.getMethod("add", classOf[Object])
            imports.foreach(hashSetAddMethod.invoke(twirlImports, _))

            // Codec.apply(Charset.forName(codec.charSet.name()))
            val twirlCodec =
              codecApplyMethod.invoke(null, charsetForNameMethod.invoke(null, codec.charSet.name()))

            // val twirlConstructorAnnotations = new ArrayList()
            // constructorAnnotations.foreach(twirlConstructorAnnotations.add)
            val twirlConstructorAnnotations = arrayListClass.newInstance().asInstanceOf[Object]
            val arrayListAddMethod =
              twirlConstructorAnnotations.getClass.getMethod("add", classOf[Object])
            constructorAnnotations.foreach(arrayListAddMethod.invoke(
              twirlConstructorAnnotations,
              _
            ))

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
            val o = compileMethod.invoke(
              null,
              source,
              sourceDirectory,
              generatedDirectory,
              formatterType,
              twirlImports,
              twirlConstructorAnnotations,
              twirlCodec,
              Boolean.box(inclusiveDot)
            )
          }
        }
        twirlInstanceCache = Some(classloaderSig -> (instance -> twirlCompilerClass))
        (instance, twirlCompilerClass)
    }
  }

  private def twirl(twirlClasspath: Agg[PathRef]): TwirlWorkerApi =
    twirlCompilerAndClass(twirlClasspath)._1

  private def twirlClass(twirlClasspath: Agg[PathRef]): Class[_] =
    twirlCompilerAndClass(twirlClasspath)._2

  def defaultImports(twirlClasspath: Agg[PathRef]): Seq[String] =
    twirlClass(twirlClasspath).getField("DEFAULT_IMPORTS")
      .get(null).asInstanceOf[java.util.Set[String]].asScala.toSeq

  def defaultFormats: Map[String, String] =
    Map(
      "html" -> "play.twirl.api.HtmlFormat",
      "xml" -> "play.twirl.api.XmlFormat",
      "js" -> "play.twirl.api.JavaScriptFormat",
      "txt" -> "play.twirl.api.TxtFormat"
    )

  def compile(
      twirlClasspath: Agg[PathRef],
      sourceDirectories: Seq[os.Path],
      dest: os.Path,
      imports: Seq[String],
      formats: Map[String, String],
      constructorAnnotations: Seq[String],
      codec: Codec,
      inclusiveDot: Boolean
  )(implicit ctx: mill.api.Ctx): mill.api.Result[CompilationResult] = {
    val compiler = twirl(twirlClasspath)
    val formatExtsRegex = formats.keys.map(Regex.quote).mkString("|")

    def compileTwirlDir(inputDir: os.Path): Unit = {
      os.walk(inputDir).filter(_.last.matches(s".*.scala.($formatExtsRegex)"))
        .foreach { template =>
          val extClass = twirlExtensionClass(template.last, formats)
          compiler.compileTwirl(
            template.toIO,
            inputDir.toIO,
            dest.toIO,
            extClass,
            imports,
            constructorAnnotations,
            codec,
            inclusiveDot
          )
        }
    }

    sourceDirectories.foreach(compileTwirlDir)

    val zincFile = ctx.dest / "zinc"
    val classesDir = ctx.dest

    mill.api.Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
  }

  private def twirlExtensionClass(name: String, formats: Map[String, String]) =
    formats.collectFirst { case (ext, klass) if name.endsWith(ext) => klass }.getOrElse {
      throw new IllegalStateException(
        s"Unknown twirl extension for file: $name. Known extensions: ${formats.keys.mkString(", ")}"
      )
    }
}

trait TwirlWorkerApi {
  def compileTwirl(
      source: File,
      sourceDirectory: File,
      generatedDirectory: File,
      formatterType: String,
      imports: Seq[String],
      constructorAnnotations: Seq[String],
      codec: Codec,
      inclusiveDot: Boolean
  ): Unit
}

object TwirlWorkerApi {

  def twirlWorker = new TwirlWorker()
}
