package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * A representation for a test module in a build that is optimized for code generation.
 */
case class TestModuleRepr(
    name: String,
    supertypes: Seq[String],
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil
)
object TestModuleRepr {
  implicit val rw: ReadWriter[TestModuleRepr] = macroRW

  val supertypeByDep: PartialFunction[(String, String), String] = {
    case ("org.testng", "testng") => "TestModule.TestNg"
    case ("junit", "junit") => "TestModule.Junit4"
    case ("org.junit.platform", "junit-platform-launcher") => "TestModule.Junit5"
    case ("org.junit.jupiter", "junit-jupiter-api") => "TestModule.Junit5"
    case ("org.scalatest", "scalatest") => "TestModule.ScalaTest"
    case ("org.scalatest", a) if a.startsWith("scalatest-") => "TestModule.ScalaTest"
    case ("org.specs2", "specs2-core") => "TestModule.Specs2"
    case ("com.lihaoyi", "utest") => "TestModule.Utest"
    case ("org.scalameta", "munit") => "TestModule.Munit"
    case ("com.disneystreaming", "weaver-scalacheck") => "TestModule.Weaver"
    case ("dev.zio", "zio-test" | "zio-test-sbt") => "TestModule.ZioTest"
    case ("org.scalacheck", "scalacheck") => "TestModule.ScalaCheck"
  }
}
