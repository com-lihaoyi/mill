package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

import scala.collection.mutable

/**
 * A representation for a test module in a build that is optimized for code generation.
 */
case class TestModuleRepr(
    name: String,
    supertypes: Seq[String],
    mixins: Seq[String] = Nil,
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil
)
object TestModuleRepr {
  implicit val rw: ReadWriter[TestModuleRepr] = macroRW

  def mixinAndMandatoryMvnDeps(mvnDeps: Iterable[String]): Option[(String, Seq[String])] = {
    val mandatoryMvnDeps = Seq.newBuilder[String]
    var mixin: String = null
    // frameworks like scalatest and specs2 provide integrations with other frameworks
    mvnDeps.foreach {
      case dep @ JavaModuleConfig.mvnDepOrgNameRegex(org, name) => (org, name) match {
          case ("org.testng", _) =>
            if (mixin == null) mixin = "TestModule.TestNg"
            mandatoryMvnDeps += dep
          case ("junit", _) =>
            if (mixin == null) mixin = "TestModule.Junit4"
            mandatoryMvnDeps += dep
          case ("org.junit.jupiter" | "org.junit.platform", _) =>
            if (mixin == null) mixin = "TestModule.Junit5"
            mandatoryMvnDeps += dep
          case ("org.scalatest" | "org.scalatestplus", _) =>
            mixin = "TestModule.ScalaTest"
            mandatoryMvnDeps += dep
          case ("org.specs2", _) =>
            mixin = "TestModule.Specs2"
            mandatoryMvnDeps += dep
          case ("com.lihaoyi", "utest") =>
            if (mixin == null) mixin = "TestModule.Utest"
            mandatoryMvnDeps += dep
          case ("org.scalameta", "munit") =>
            if (mixin == null) mixin = "TestModule.Munit"
            mandatoryMvnDeps += dep
          case ("com.disneystreaming", "weaver-scalacheck") =>
            if (mixin == null) mixin = "TestModule.Weaver"
            mandatoryMvnDeps += dep
          case ("dev.zio", "zio-test" | "zio-test-sbt") =>
            if (mixin == null) mixin = "TestModule.ZioTest"
            mandatoryMvnDeps += dep
          case ("org.scalacheck", "scalacheck") =>
            if (mixin == null) mixin = "TestModule.ScalaCheck"
            mandatoryMvnDeps += dep
          case _ =>
        }
    }
    if (null == mixin) None
    else Some((mixin, mandatoryMvnDeps.result()))
  }
}
