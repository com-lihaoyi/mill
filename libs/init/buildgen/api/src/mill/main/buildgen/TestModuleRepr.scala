package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

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

  val settingsByDep: PartialFunction[(String, String, String), (String, String)] = {
    case ("org.testng", "testng", v) => ("testngVersion", v)
    case ("junit", "junit", v) => ("junit4Version", v)
    case ("org.junit.platform", "junit-platform-launcher", v) => ("junitPlatformVersion", v)
    case ("org.junit.jupiter", "junit-jupiter-api", v) => ("jupiterVersion", v)
    case ("org.scalatest", "scalatest", v) => ("scalaTestVersion", v)
    case ("org.scalatest", a, _) if a.startsWith("scalatest-") =>
      ("scalaTestStyles", a.substring("scalatest-".length))
    case ("org.specs2", "specs2-core", v) => ("specs2Version", v)
    case ("com.lihaoyi", "utest", v) => ("utestVersion", v)
    case ("org.scalameta", "munit", v) => ("munitVersion", v)
    case ("com.disneystreaming", "weaver-scalacheck", v) => ("weaverVersion", v)
    case ("dev.zio", "zio-test" | "zio-test-sbt", v) => ("zioTestVersion", v)
    case ("org.scalacheck", "scalacheck", v) => ("scalaCheckVersion", v)
  }
}
