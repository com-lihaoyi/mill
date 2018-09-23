package mill.contrib

import ammonite.ops.write
import mill.T
import mill.define.Target
import mill.eval.PathRef
import mill.scalalib.ScalaModule
import mill.util.Ctx

trait BuildInfo extends ScalaModule {

  def buildInfoPackageName: Option[String] = None

  def buildInfoObjectName: String = "BuildInfo"

  def buildInfoMembers: Map[String, Any] = Map.empty[String, Any]

  private def generateBuildInfo(members: Map[String, Any])(implicit dest: Ctx.Dest): Seq[PathRef] =
  if(!members.isEmpty){
    val outputFile = dest.dest / "BuildInfo.scala"
    val internalMembers =
      members
        .map {
          case (name, value: Boolean) => s"""  def ${name}: Boolean = ${if(value) "true" else "false"}"""
          case (name, value: Byte) => s"""  def ${name}: Byte = ${value}"""
          case (name, value: Short) => s"""  def ${name}: Short = ${value}"""
          case (name, value: Int) => s"""  def ${name}: Int = ${value}"""
          case (name, value: Long) => s"""  def ${name}: Long = ${value}l"""
          case (name, value: Float) => s"""  def ${name}: Float = ${value}f"""
          case (name, value: Double) => s"""  def ${name}: Double = ${value}d"""
          case (name, value: Char) => s"""  def ${name}: Char = '${value}'"""
          case (name, value) => s"""  def ${name}: String = "${value}""""
        }
        .mkString("\n")
    write(outputFile,
      s"""|${buildInfoPackageName.map(p => s"package ${p}").getOrElse("")}
          |object ${buildInfoObjectName} {
          |$internalMembers
          |}""".stripMargin)
    Seq(PathRef(outputFile))
  } else {
    Seq.empty[PathRef]
  }

  def buildInfo = T {
    generateBuildInfo(buildInfoMembers)
  }

  override def generatedSources: Target[Seq[PathRef]] = super.generatedSources() ++ buildInfo()

}
