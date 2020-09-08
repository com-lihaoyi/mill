package mill.contrib.buildinfo

import mill.T
import mill.Agg
import mill.api.{ Logger, Loose, PathRef }
import mill.define.Target
import mill.scalalib.{ Dep, ScalaModule, DepSyntax }

trait BuildInfo extends ScalaModule {

  def buildInfoPackageName: Option[String] = None

  def buildInfoObjectName: String = "BuildInfo"

  def buildInfoMembers: T[Map[String, String]] = T {
    Map.empty[String, String]
  }

  def generatedBuildInfo: T[(Seq[PathRef], PathRef)] = T {
    val logger: Logger = T.log
    val members: Map[String, String] = buildInfoMembers()
    if (members.nonEmpty) {
      val outputFile = T.dest / "BuildInfo.scala"
      val internalMembers =
        members
          .map {
            case (name, value) => s"""  def ${name} = "${value}""""
          }
          .mkString("\n")
      val map = members.map {
        case (name, _) => s""""${name}" -> ${name}"""
      }.mkString(",")
      logger.debug(s"Generating object [${buildInfoPackageName.map(_ + ".").getOrElse("")}${buildInfoObjectName}] with [${members.size}] members to [${outputFile}]")
      os.write(
        outputFile,
        s"""|${buildInfoPackageName.map(packageName => s"package ${packageName}\n").getOrElse("")}
            |object ${buildInfoObjectName} {
            |$internalMembers
            |
            |  val toMap = Map[String, String](
            |    $map)
            |}""".stripMargin
        )
      (Seq(PathRef(outputFile)), PathRef(T.dest))
    } else {
      logger.debug("No build info member defined, skipping code generation")
      (Seq.empty[PathRef], PathRef(T.dest))
    }
  }

  override def generatedSources = T {
    val (_, destPathRef) = generatedBuildInfo()
    super.generatedSources() :+ destPathRef
  }

}
