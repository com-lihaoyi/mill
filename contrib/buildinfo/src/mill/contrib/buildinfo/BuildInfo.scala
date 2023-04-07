package mill.contrib.buildinfo

import mill.{PathRef, T}
import mill.scalalib.{JavaModule, ScalaModule}

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
  def buildInfoStaticCompiled: Boolean = false

  /**
   * A mapping of key-value pairs to pass from the Build script to the
   * application code at runtime.
   */
  def buildInfoMembers: T[Seq[BuildInfo.Value]]

  def resources =
    if (buildInfoStaticCompiled) super.resources
    else T.sources{
      val p = new java.util.Properties
      for(v <- buildInfoMembers()) p.setProperty(v.key, v.value)

      val stream = os.write.outputStream(
        T.dest / os.SubPath(buildInfoPackageName.replace('.', '/')) / "mill-build-info.properties",
        createFolders = true
      )

      p.store(stream, s"mill.contrib.buildinfo.BuildInfo for package ${buildInfoPackageName}")
      stream.close()

      super.resources() ++ Seq(PathRef(T.dest))
    }

  private def isScala = this.isInstanceOf[ScalaModule]

  override def generatedSources = T {
    super.generatedSources() ++ generatedBuildInfo()
  }

  def generatedBuildInfo = T{
    if (buildInfoMembers().isEmpty) Nil
    else {
      val code = if (buildInfoStaticCompiled) BuildInfo.staticCompiledCodegen(
        buildInfoMembers(), isScala, buildInfoPackageName, buildInfoObjectName
      ) else BuildInfo.codegen(
        buildInfoMembers(), isScala, buildInfoPackageName, buildInfoObjectName
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

object BuildInfo{
  case class Value(key: String, value: String, comment: String = "")
  object Value{
    implicit val rw: upickle.default.ReadWriter[Value] = upickle.default.macroRW
  }
  def staticCompiledCodegen(buildInfoMembers: Seq[Value],
                            isScala: Boolean,
                            buildInfoPackageName: String,
                            buildInfoObjectName: String): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          if (isScala) s"""${commentStr(v)}val ${v.key} = ${pprint.Util.literalize(v.value)}"""
          else s"""${commentStr(v)}public static java.lang.String ${v.key} = ${pprint.Util.literalize(v.value)};"""
      }
      .mkString("\n\n  ")


    if (isScala) {
      val mapEntries = buildInfoMembers
        .map { case v => s""""${v.key}" -> ${v.key}"""}
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
         |  public static java.util.Map<String, String> toMap(){
         |    Map<String, String> map = new HashMap<String, String>();
         |    $mapEntries
         |    return map;
         |  }
         |}
      """.stripMargin.trim
    }
  }

  def codegen(buildInfoMembers: Seq[Value],
              isScala: Boolean,
              buildInfoPackageName: String,
              buildInfoObjectName: String): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          if (isScala) s"""${commentStr(v)}val ${v.key} = buildInfoProperties.getProperty("${v.key}")"""
          else s"""${commentStr(v)}public static final java.lang.String ${v.key} = buildInfoProperties.getProperty("${v.key}");"""
      }
      .mkString("\n\n  ")

    if (isScala)
      s"""
         |package ${buildInfoPackageName}
         |
         |object $buildInfoObjectName {
         |  private val buildInfoProperties = new java.util.Properties
         |
         |  private val buildInfoInputStream = getClass
         |    .getClassLoader
         |    .getResourceAsStream("${buildInfoPackageName.replace('.', '/')}/mill-build-info.properties")
         |
         |  buildInfoProperties.load(buildInfoInputStream)
         |
         |  $bindingsCode
         |}
      """.stripMargin.trim
    else
      s"""
         |package ${buildInfoPackageName};
         |
         |public class $buildInfoObjectName {
         |  private static java.util.Properties buildInfoProperties = new java.util.Properties();
         |
         |  static {
         |    java.io.InputStream buildInfoInputStream = $buildInfoObjectName
         |      .class
         |      .getClassLoader()
         |      .getResourceAsStream("${buildInfoPackageName.replace('.', '/')}/mill-build-info.properties");
         |
         |    try{
         |      buildInfoProperties.load(buildInfoInputStream);
         |    }catch(java.io.IOException e){
         |      throw new RuntimeException(e);
         |    }finally{
         |      try{
         |        buildInfoInputStream.close();
         |      }catch(java.io.IOException e){
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
      lines.length match{
        case 1 => s"""/** ${v.comment} */\n  """
        case _ => s"""/**\n    ${lines.map("* " + _).mkString("\n    ")}\n    */\n  """
      }

    }
  }
}
