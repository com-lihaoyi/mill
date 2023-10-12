package mill.contrib.buildinfo

import mill.{PathRef, T}
import mill.scalalib.{JavaModule, ScalaModule}
import mill.scalanativelib.ScalaNativeModule
import mill.scalajslib.ScalaJSModule

trait BuildInfo extends JavaModule {

  /**
   * The package name under which the BuildInfo data object will be stored.
   */
  def buildInfoPackageName: String

  /**
   * The name of the BuildInfo data object, defaults to "BuildInfo"
   */
  def buildInfoObjectName: String = "BuildInfo"

  /**
   * Enable to compile the BuildInfo values directly into the classfiles,
   * rather than the default behavior of storing them as a JVM resource. Needed
   * to use BuildInfo on Scala.js which does not support JVM resources
   */
  def buildInfoStaticCompiled: Boolean = this match {
    case _: ScalaJSModule => true
    case _: ScalaNativeModule => true
    case _ => false
  }

  /**
   * A mapping of key-value pairs to pass from the Build script to the
   * application code at runtime.
   */
  def buildInfoMembers: T[Seq[BuildInfo.Value]] = Seq.empty[BuildInfo.Value]

  def resources =
    if (buildInfoStaticCompiled) super.resources
    else T.sources { super.resources() ++ Seq(buildInfoResources()) }

  def buildInfoResources = T {
    val p = new java.util.Properties
    for (v <- buildInfoMembers()) p.setProperty(v.key, v.value)

    val subPath = os.SubPath(buildInfoPackageName.replace('.', '/'))
    val stream = os.write.outputStream(
      T.dest / subPath / s"$buildInfoObjectName.buildinfo.properties",
      createFolders = true
    )

    p.store(
      stream,
      s"mill.contrib.buildinfo.BuildInfo for ${buildInfoPackageName}.${buildInfoObjectName}"
    )
    stream.close()
    PathRef(T.dest)
  }

  private def isScala = this.isInstanceOf[ScalaModule]

  override def generatedSources = T {
    super.generatedSources() ++ buildInfoSources()
  }

  def buildInfoSources = T {
    if (buildInfoMembers().isEmpty) Nil
    else {
      val code = if (buildInfoStaticCompiled) BuildInfo.staticCompiledCodegen(
        buildInfoMembers(),
        isScala,
        buildInfoPackageName,
        buildInfoObjectName
      )
      else BuildInfo.codegen(
        buildInfoMembers(),
        isScala,
        buildInfoPackageName,
        buildInfoObjectName
      )

      val ext = if (isScala) "scala" else "java"

      os.write(
        T.dest / buildInfoPackageName.split('.') / s"${buildInfoObjectName}.$ext",
        code,
        createFolders = true
      )
      Seq(PathRef(T.dest))
    }
  }
}

object BuildInfo {
  case class Value(key: String, value: String, comment: String = "")
  object Value {
    implicit val rw: upickle.default.ReadWriter[Value] = upickle.default.macroRW
  }
  def staticCompiledCodegen(
      buildInfoMembers: Seq[Value],
      isScala: Boolean,
      buildInfoPackageName: String,
      buildInfoObjectName: String
  ): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          if (isScala) s"""${commentStr(v)}val ${v.key} = ${pprint.Util.literalize(v.value)}"""
          else s"""${commentStr(
              v
            )}public static java.lang.String ${v.key} = ${pprint.Util.literalize(v.value)};"""
      }
      .mkString("\n\n  ")

    if (isScala) {
      val mapEntries = buildInfoMembers
        .map { case v => s""""${v.key}" -> ${v.key}""" }
        .mkString(",\n")

      s"""
         |package $buildInfoPackageName
         |
         |object $buildInfoObjectName {
         |  $bindingsCode
         |  val toMap = Map[String, String](
         |    $mapEntries
         |  )
         |}
      """.stripMargin.trim
    } else {
      val mapEntries = buildInfoMembers
        .map { case v => s"""map.put("${v.key}", ${v.key});""" }
        .mkString(",\n")

      s"""
         |package $buildInfoPackageName;
         |
         |public class $buildInfoObjectName {
         |  $bindingsCode
         |
         |  public static java.util.Map<String, String> toMap() {
         |    Map<String, String> map = new HashMap<String, String>();
         |    $mapEntries
         |    return map;
         |  }
         |}
      """.stripMargin.trim
    }
  }

  def codegen(
      buildInfoMembers: Seq[Value],
      isScala: Boolean,
      buildInfoPackageName: String,
      buildInfoObjectName: String
  ): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          if (isScala)
            s"""${commentStr(v)}val ${v.key} = buildInfoProperties.getProperty("${v.key}")"""
          else s"""${commentStr(
              v
            )}public static final java.lang.String ${v.key} = buildInfoProperties.getProperty("${v.key}");"""
      }
      .mkString("\n\n  ")

    if (isScala)
      s"""
         |package ${buildInfoPackageName}
         |
         |object $buildInfoObjectName {
         |  private[this] val buildInfoProperties: java.util.Properties = new java.util.Properties()
         |
         |  {
         |    val buildInfoInputStream = getClass
         |      .getResourceAsStream("${buildInfoObjectName}.buildinfo.properties")
         |
         |    if(buildInfoInputStream == null)
         |      throw new RuntimeException("Could not load resource ${buildInfoObjectName}.buildinfo.properties")
         |    else try {
         |      buildInfoProperties.load(buildInfoInputStream)
         |    } finally {
         |      buildInfoInputStream.close()
         |    }
         |  }
         |
         |  $bindingsCode
         |}
      """.stripMargin.trim
    else
      s"""
         |package ${buildInfoPackageName};
         |
         |public class $buildInfoObjectName {
         |  private static final java.util.Properties buildInfoProperties = new java.util.Properties();
         |
         |  static {
         |    java.io.InputStream buildInfoInputStream = ${buildInfoObjectName}
         |      .class
         |      .getResourceAsStream("${buildInfoObjectName}.buildinfo.properties");
         |
         |    try {
         |      buildInfoProperties.load(buildInfoInputStream);
         |    } catch (java.io.IOException e) {
         |      throw new RuntimeException(e);
         |    } finally {
         |      try {
         |        buildInfoInputStream.close();
         |      } catch (java.io.IOException e) {
         |        throw new RuntimeException(e);
         |      }
         |    }
         |  }
         |
         |  $bindingsCode
         |}
      """.stripMargin.trim
  }

  def commentStr(v: Value) = {
    if (v.comment.isEmpty) ""
    else {
      val lines = v.comment.linesIterator.toVector
      lines.length match {
        case 1 => s"""/** ${v.comment} */\n  """
        case _ => s"""/**\n    ${lines.map("* " + _).mkString("\n    ")}\n    */\n  """
      }

    }
  }
}
