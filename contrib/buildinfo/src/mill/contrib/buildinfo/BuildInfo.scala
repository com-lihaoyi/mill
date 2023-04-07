package mill.contrib.buildinfo

import mill.{PathRef, T}
import mill.scalalib.{JavaModule, ScalaModule}

trait BuildInfo extends JavaModule {
  def buildInfoPackageName: String
  def buildInfoStaticCompiled: Boolean = false
  def buildInfoMembers: T[Map[String, String]] = Map.empty[String, String]
  def buildInfoObjectName: String = "BuildInfo"

  def resources =
    if (buildInfoStaticCompiled) super.resources
    else T.sources{
      for((k, v) <- buildInfoMembers()) os.write(
        T.dest / os.SubPath(buildInfoPackageName.replace('.', '/')) / s"$k.buildinfo",
        v.getBytes("UTF-8"),
        createFolders = true
      )

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
  def staticCompiledCodegen(buildInfoMembers: Map[String, String],
                            isScala: Boolean,
                            buildInfoPackageName: String,
                            buildInfoObjectName: String): String = {
    val bindingsCode = buildInfoMembers
      .toSeq
      .sortBy(_._1)
      .map {
        case (k, v) =>
          if (isScala) s"""val $k = ${pprint.Util.literalize(v)}"""
          else s"""public static java.lang.String $k = ${pprint.Util.literalize(v)};"""
      }
      .mkString("\n")


    if (isScala) {
      val mapEntries = buildInfoMembers
        .map { case (name, _) => s""""$name" -> $name"""}
        .mkString(",\n")

      s"""
         |package ${buildInfoPackageName}
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
        .map { case (name, _) => s"""map.put("$name", $name)""" }
        .mkString(",\n")

      s"""
         |package ${buildInfoPackageName};
         |
         |public class $buildInfoObjectName {
         |  $bindingsCode
         |
         |  public static java.util.Map<String, String> toMap(){
         |    Map<String, String> map = new HashMap<String, String>()
         |    $mapEntries
         |    return map;
         |  }
         |}
      """.stripMargin.trim
    }
  }
  def codegen(buildInfoMembers: Map[String, String],
              isScala: Boolean,
              buildInfoPackageName: String,
              buildInfoObjectName: String): String = {
    val bindingsCode = buildInfoMembers
      .toSeq
      .sortBy(_._1)
      .map {
        case (k, v) =>
          if (isScala) s"""val $k = this.readMillBuildInfo("$k")"""
          else s"""public static java.lang.String $k = readMillBuildInfo("$k");"""
      }
      .mkString("\n")



    if (isScala)
      s"""
         |package ${buildInfoPackageName}
         |
         |object $buildInfoObjectName {
         |  def readMillBuildInfo(key: String) = {
         |    val inputStream = getClass
         |      .getClassLoader
         |      .getResourceAsStream("${buildInfoPackageName.replace('.', '/')}/" + key + ".buildinfo")
         |
         |    if (inputStream == null) throw new RuntimeException("Cannot find buildinfo key: " + key)
         |    val into = new java.io.ByteArrayOutputStream()
         |    val buf = new Array[Byte](4096)
         |    var n = 0
         |    while ({
         |      val n = inputStream.read(buf)
         |      if (0 < n){
         |        into.write(buf, 0, n)
         |        true
         |      } else false
         |    })()
         |    into.close
         |    inputStream.close()
         |    new String(into.toByteArray, "UTF-8") // Or whatever encoding
         |  }
         |  $bindingsCode
         |}
      """.stripMargin.trim
    else
      s"""
         |package ${buildInfoPackageName};
         |
         |public class $buildInfoObjectName {
         |  private static String readMillBuildInfo(String key) {
         |    try{
         |      java.io.InputStream inputStream = $buildInfoObjectName
         |        .class.getClassLoader()
         |        .getResourceAsStream("${buildInfoPackageName.replace('.', '/')}/" + key + ".buildinfo");
         |
         |      if (inputStream == null) throw new RuntimeException("Cannot find buildinfo key: " + key);
         |      java.io.ByteArrayOutputStream into = new java.io.ByteArrayOutputStream();
         |      byte[] buf = new byte[4096];
         |      for (int n; 0 < (n = inputStream.read(buf));) {
         |          into.write(buf, 0, n);
         |      }
         |      into.close();
         |      inputStream.close();
         |      return new String(into.toByteArray(), "UTF-8"); // Or whatever encoding
         |    }catch(java.io.IOException e){ throw new RuntimeException(e); }
         |  }
         |  $bindingsCode
         |}
      """.stripMargin.trim
  }
}
