package mill.main.sbt

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.internal.Util.backtickWrap
import mill.main.buildgen.*
import pprint.Util.literalize

object SbtBuildWriter extends BuildWriter {

  def renderImports(pkg: Tree[ModuleRepr]) = {
    val b = Set.newBuilder[String]
    b += "mill.scalalib._"
    for module <- pkg.iterator do
      if (module.configs.isEmpty || module.crossConfigs.nonEmpty) b += "mill.api._"
      if (module.testModule.exists(_.supertypes.exists(_.startsWith("TestModule"))))
        b += "mill.javalib._"
      module.configs.foreach:
        case _: ScalaJSModuleConfig => b += "mill.scalajslib._"
        case _: ScalaNativeModuleConfig => b += "mill.scalanativelib._"
        case _: ScalaModuleConfig => b += "mill.scalalib._"
        case _: PublishModuleConfig => b += "mill.javalib.publish._"
        case _: JavaModuleConfig | CoursierModuleConfig => b += "mill.javalib._"
        case _ =>
    end for
    if (pkg.root.segments.nonEmpty) b += s"$rootModuleAlias._"

    renderLines(b.result().toSeq.sorted.iterator.map(s => s"import $s"))
  }

  override def renderCrossModuleDeclaration(name: String, module: ModuleRepr) = {
    import module.*
    val crossTraitName = segments.lastOption.getOrElse(os.pwd.last).split("\\W").map(_.capitalize)
      .mkString("", "", "Module")
    val crossTraitExtends = crossConfigs.map((v, _) => literalize(v))
      .mkString(s"extends Cross[$crossTraitName](", ", ", ")")
    s"""object ${backtickWrap(name)} $crossTraitExtends
       |trait $crossTraitName ${renderExtendsClause(supertypes ++ mixins)}""".stripMargin
  }

  def renderCrossValue[A](
      crossValues: Seq[(String, A)],
      renderValue: A => String,
      defaultValue: String
  ): String =
    s"""scalaVersion() match {
       |  ${renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((value, crosses) =>
        s"case ${crosses.map(literalize(_)).mkString(" | ")} => ${renderValue(value)}"
      ))}
       |  case _ => $defaultValue
       |}""".stripMargin

  def renderAppendedCrossValues[A](crossValues: Seq[(String, Seq[A])], renderValue: A => String) =
    if (crossValues.isEmpty) ""
    else
      s""" ++ (scalaVersion() match {
         |  ${renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((values, crosses) =>
          s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(${values.iterator.map(renderValue).mkString(", ")})"
        ))}
         |  case _ => Nil
         |})""".stripMargin
}
