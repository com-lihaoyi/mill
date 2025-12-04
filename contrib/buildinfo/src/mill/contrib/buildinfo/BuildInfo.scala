package mill.contrib.buildinfo

import mill.{T, Task}
import mill.api.PathRef
import mill.kotlinlib.KotlinModule
import mill.scalalib.{JavaModule, ScalaModule}
import mill.scalanativelib.ScalaNativeModule
import mill.scalajslib.ScalaJSModule

trait BuildInfo extends JavaModule {

  /**
   * The name of the BuildInfo data object which contains all the members
   * from [[buildInfoMembers]]. Defaults to "BuildInfo"
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
   * The source language to use for the generated source file(s).
   */
  def buildInfoLanguage: BuildInfo.Language = this match {
    case _: ScalaModule => BuildInfo.Language.Scala
    case _: KotlinModule => BuildInfo.Language.Kotlin
    case _ => BuildInfo.Language.Java
  }

  /**
   * A mapping of key-value pairs to pass from the Build script to the
   * application code at runtime.
   */
  def buildInfoMembers: T[Seq[BuildInfo.Value]] = Seq()

  def resources: T[Seq[PathRef]] =
    if (buildInfoStaticCompiled) Task { super.resources() }
    else Task { super.resources() ++ Seq(buildInfoResources()) }

  def buildInfoResources = Task {
    val p = new java.util.Properties
    for (v <- buildInfoMembers()) p.setProperty(v.key, v.value)

    val subPath = os.SubPath(buildInfoPackageName.replace('.', '/'))
    val stream = os.write.outputStream(
      Task.dest / subPath / s"$buildInfoObjectName.buildinfo.properties",
      createFolders = true
    )

    p.store(
      stream,
      s"mill.contrib.buildinfo.BuildInfo for ${buildInfoPackageName}.${buildInfoObjectName}"
    )
    stream.close()
    PathRef(Task.dest)
  }

  override def generatedSources = Task {
    super.generatedSources() ++ buildInfoSources()
  }

  def buildInfoSources = Task {
    if (buildInfoMembers().isEmpty) Nil
    else {
      val code = if (buildInfoStaticCompiled) BuildInfo.staticCompiledCodegen(
        buildInfoMembers(),
        buildInfoLanguage,
        buildInfoPackageName,
        buildInfoObjectName
      )
      else BuildInfo.codegen(
        buildInfoMembers(),
        buildInfoLanguage,
        buildInfoPackageName,
        buildInfoObjectName
      )

      os.write(
        Task.dest / buildInfoPackageName.split('.') /
          s"${buildInfoObjectName}.${buildInfoLanguage.ext}",
        code,
        createFolders = true
      )
      Seq(PathRef(Task.dest))
    }
  }
}

object BuildInfo {
  enum Language(val ext: String) {
    case Java extends Language("java")
    case Scala extends Language("scala")
    case Kotlin extends Language("kt")
  }

  case class Value(key: String, value: String, comment: String = "")
  object Value {
    implicit val rw: upickle.ReadWriter[Value] = upickle.macroRW
  }
  def staticCompiledCodegen(
      buildInfoMembers: Seq[Value],
      language: Language,
      buildInfoPackageName: String,
      buildInfoObjectName: String
  ): String = {

    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          language match {
            case Language.Scala | Language.Kotlin =>
              s"""${commentStr(v)}val ${v.key}: String = ${pprint.Util.literalize(v.value)}"""
            case Language.Java => s"""${commentStr(
                  v
                )}public static java.lang.String ${v.key} = ${pprint.Util.literalize(v.value)};"""
          }
      }
      .mkString("\n\n  ")

    language match {
      case Language.Scala =>
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

      case Language.Kotlin =>
        val mapEntries = buildInfoMembers
          .map { case v => s""""${v.key}" to ${v.key}""" }
          .mkString(",\n")

        s"""
           |package $buildInfoPackageName
           |
           |object $buildInfoObjectName {
           |  $bindingsCode
           |  val toMap: Map<String, String> = mapOf(
           |    $mapEntries
           |  )
           |}
      """.stripMargin.trim

      case Language.Java =>
        val mapEntries = buildInfoMembers
          .map { case v => s"""map.put("${v.key}", ${v.key});""" }
          .mkString(",\n")

        s"""
           |package $buildInfoPackageName;
           |
           |public class $buildInfoObjectName {
           |  $bindingsCode
           |
           |  public static java.util.Map<java.lang.String, java.lang.String> toMap() {
           |    java.util.Map<java.lang.String, java.lang.String> map = new java.util.HashMap<java.lang.String, java.lang.String>();
           |    $mapEntries
           |    return map;
           |  }
           |}
      """.stripMargin.trim
    }
  }

  def codegen(
      buildInfoMembers: Seq[Value],
      language: Language,
      buildInfoPackageName: String,
      buildInfoObjectName: String
  ): String = {
    val bindingsCode = buildInfoMembers
      .sortBy(_.key)
      .map {
        case v =>
          language match {
            case Language.Scala | Language.Kotlin =>
              s"""${commentStr(v)}val ${v.key} = buildInfoProperties.getProperty("${v.key}")"""
            case Language.Java =>
              val propValue = s"""buildInfoProperties.getProperty("${v.key}")"""
              s"""${commentStr(v)}public static final java.lang.String ${v.key} = $propValue;"""
          }
      }
      .mkString("\n\n  ")

    language match {
      case Language.Scala =>
        s"""
           |package ${buildInfoPackageName}
           |
           |object $buildInfoObjectName {
           |  private val buildInfoProperties: java.util.Properties = new java.util.Properties()
           |
           |  {
           |    val buildInfoInputStream = getClass
           |      .getResourceAsStream("${buildInfoObjectName}.buildinfo.properties")
           |
           |    if(buildInfoInputStream == null)
           |      throw RuntimeException("Could not load resource ${buildInfoObjectName}.buildinfo.properties")
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

      case Language.Kotlin =>
        s"""
           |package ${buildInfoPackageName}
           |
           |object $buildInfoObjectName {
           |  private val buildInfoProperties: java.util.Properties = java.util.Properties()
           |
           |  init {
           |    val buildInfoInputStream = ${buildInfoObjectName}::class.java
           |      .getResourceAsStream("${buildInfoObjectName}.buildinfo.properties")
           |
           |    if(buildInfoInputStream == null)
           |      throw RuntimeException("Could not load resource ${buildInfoObjectName}.buildinfo.properties")
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

      case Language.Java =>
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
           |      throw RuntimeException(e);
           |    } finally {
           |      try {
           |        buildInfoInputStream.close();
           |      } catch (java.io.IOException e) {
           |        throw RuntimeException(e);
           |      }
           |    }
           |  }
           |
           |  $bindingsCode
           |}
      """.stripMargin.trim
    }
  }

  def commentStr(v: Value): String = {
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
