package mill.init.migrate

import upickle.default.{ReadWriter, macroRW}

case class ModuleTypedef(name: String, supertypes: Seq[String], configs: Seq[ModuleConfig]) {

  def extend(supertypes: String*): ModuleTypedef =
    copy(supertypes = this.supertypes ++ supertypes)
}
object ModuleTypedef {

  def apply(name: String = "package", configs: Seq[ModuleConfig] = Nil): ModuleTypedef =
    apply(name, computeSupertypes(configs), configs)

  def computeSupertypes(configs: Seq[ModuleConfig]) = {
    var supertypes = configs.collectFirst {
      case _: ScalaJSModuleConfig => "ScalaJSModule"
      case _: ScalaNativeModuleConfig => "ScalaNativeModule"
    }.toSeq
    if (supertypes.isEmpty && configs.exists(_.isInstanceOf[ScalaModuleConfig]))
      supertypes :+= "ScalaModule"
    if (configs.exists(_.isInstanceOf[PublishModuleConfig])) supertypes :+= "PublishModule"
    if (supertypes.isEmpty && configs.exists(_.isInstanceOf[JavaModuleConfig]))
      supertypes :+= "JavaModule"
    if (supertypes.isEmpty && configs.exists(_.isInstanceOf[CoursierModuleConfig]))
      supertypes :+= "CoursierModule"
    if (supertypes.isEmpty) Seq("Module") else supertypes
  }

  def computeTestSupertypes(mainSupertypes: Seq[String]) = {
    var supertypes = mainSupertypes.collectFirst {
      case "ScalaJSModule" => "ScalaJSTests"
      case "ScalaNativeModule" => "ScalaNativeTests"
    }.toSeq
    if (supertypes.isEmpty && mainSupertypes.contains("ScalaModule"))
      supertypes :+= "ScalaTests"
    if (supertypes.isEmpty && mainSupertypes.contains("JavaModule"))
      supertypes :+= "JavaTests"
    supertypes
  }

  val testSupertypeByDepOrg = Map(
    "junit" -> "TestModule.Junit4",
    "org.junit.jupiter" -> "TestModule.Junit5",
    "org.testng" -> "TestModule.TestNg",
    "org.scalatest" -> "TestModule.ScalaTest",
    "org.specs2" -> "TestModule.Specs2",
    "com.lihaoyi" -> "TestModule.Utest",
    "org.scalameta" -> "TestModule.Munit",
    "com.disneystreaming" -> "TestModule.Weaver",
    "dev.zio" -> "TestModule.ZioTest",
    "org.scalacheck" -> "TestModule.ScalaCheck"
  )

  implicit val rw: ReadWriter[ModuleTypedef] = macroRW
}
