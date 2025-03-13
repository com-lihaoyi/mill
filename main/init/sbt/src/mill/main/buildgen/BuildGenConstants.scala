package mill.main.buildgen

object BuildGenConstants:

  val rootMagicImport: String = "import $packages._"

  val testRunnerModuleByOrg: Map[String, String] = Map(
    "org.testng" -> "TestModule.TestNg",
    "junit" -> "TestModule.Junit4",
    "org.junit.jupiter" -> "TestModule.Junit5",
    "org.scalatest" -> "TestModule.ScalaTest",
    "org.specs2" -> "TestModule.Specs2",
    "org.scalameta" -> "TestModule.Munit",
    "com.disneystreaming" -> "TestModule.Weaver",
    "dev.zio" -> "TestModule.ZioTest",
    "org.scalacheck" -> "TestModule.ScalaCheck",
    "com.lihaoyi" -> "TestModule.UTest"
  )
