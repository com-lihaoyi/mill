package mill.contrib

import mill.T
import mill.define.Target
import mill.eval.PathRef
import mill.scalalib.ScalaModule
import mill.util.Ctx

trait BuildInfo extends ScalaModule {

  def buildInfoPackageName: Option[String] = None

  def buildInfoObjectName: String = "BuildInfo"

  def buildInfoMembers: T[Map[String, String]] = T {
    Map.empty[String, String]
  }

  private def generateBuildInfo(members: Map[String, Any])(implicit dest: Ctx.Dest): Seq[PathRef] =
  if(!members.isEmpty){
    val outputFile = dest.dest / "BuildInfo.scala"
    val internalMembers =
      members
        .map {
          case (name, value) => s"""  def ${name} = "${value}""""
        }
        .mkString("\n")
    os.write(outputFile,
      s"""|${buildInfoPackageName.map(p => s"package ${p}").getOrElse("")}
          |object ${buildInfoObjectName} {
          |$internalMembers
          |}""".stripMargin)
    Seq(PathRef(outputFile))
  } else {
    Seq.empty[PathRef]
  }

  def buildInfo = T {
    generateBuildInfo(buildInfoMembers())
  }

  override def generatedSources: Target[Seq[PathRef]] = super.generatedSources() ++ buildInfo()

}
