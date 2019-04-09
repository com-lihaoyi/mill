package mill.contrib.buildinfo

import mill.T
import mill.api.Logger
import mill.api.PathRef
import mill.scalalib.ScalaModule

trait BuildInfo extends ScalaModule {

  def buildInfoPackageName: Option[String] = None

  def buildInfoObjectName: String = "BuildInfo"

  def buildInfoMembers: T[Map[String, String]] = T {
    Map.empty[String, String]
  }

  def generatedBuildInfo: T[Seq[PathRef]] = T {
    val logger: Logger = T.ctx.log
    val members: Map[String, String] = buildInfoMembers()
    if (members.nonEmpty) {
      val outputFile = T.ctx.dest / "BuildInfo.scala"
      val internalMembers =
        members
          .map {
            case (name, value) => s"""  def ${name} = "${value}""""
          }
          .mkString("\n")
      logger.debug(s"Generating object [${buildInfoPackageName.map(_ + ".").getOrElse("")}${buildInfoObjectName}] with [${members.size}] members to [${outputFile}]")
      os.write(
        outputFile,
        s"""|${buildInfoPackageName.map(packageName => s"package ${packageName}\n").getOrElse("")}
            |object ${buildInfoObjectName} {
            |$internalMembers
            |}""".stripMargin
      )
      Seq(PathRef(outputFile))
    } else {
      logger.debug("No build info member defined, skipping code generation")
      Seq.empty[PathRef]
    }
  }

  override def generatedSources = T {
    super.generatedSources() ++
      generatedBuildInfo().map(pathRef => PathRef(pathRef.path / os.up))
  }

}
