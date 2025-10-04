package mill.main.buildgen

object BuildConventions {

  def findJavaHomeModuleConfig(
      javaVersion: Option[Int] = None,
      javacOptions: Seq[String] = Nil
  ): Option[JavaHomeModuleConfig] = {
    def version(option: String) = javacOptions.indexOf(option) match {
      case -1 => None
      case i => javacOptions.lift(i + 1).map(_.stripPrefix("1.").toInt)
    }
    javaVersion
      .orElse(version("--release").orElse(version("-target")))
      .map { version =>
        // Mill requires Java 11+
        val version0 = 11.max(version)
        // use a distribution that provides all Java versions
        JavaHomeModuleConfig(jvmId = s"zulu:$version0")
      }
  }

  def findErrorProneModuleConfigJavacOptions(
      javacOptions: Seq[String],
      errorProneMvnDeps: Seq[ModuleConfig.MvnDep] = Nil
  ): (Option[ErrorProneModuleConfig], Seq[String]) = {
    javacOptions.find(_.startsWith("-Xplugin:ErrorProne")).map { epOption =>
      val epOptions = epOption.split("\\s+").toSeq.tail
      val epDep = errorProneMvnDeps.find(dep =>
        dep.organization == "com.google.errorprone" && dep.name == "error_prone_core"
      )
      val (epJavacOptions, javacOptions0) = javacOptions
        // skip options added by ErrorProneModule
        .filter(s => s != epOption && s != "-XDcompilePolicy=simple")
        .partition(_.startsWith("-XD"))
      val epModuleConfig = Some(ErrorProneModuleConfig(
        errorProneVersion = epDep.flatMap(_.version).orNull,
        errorProneOptions = epOptions,
        errorProneJavacEnableOptions = epJavacOptions,
        errorProneDeps = errorProneMvnDeps.diff(epDep.toSeq)
      ))
      (epModuleConfig, javacOptions0)
    }.getOrElse((None, javacOptions))
  }

  def findTestModuleMixin(mvnDeps: Seq[ModuleConfig.MvnDep]): Option[String] =
    // find mixins that integrate with other frameworks first
    mvnDeps.collectFirst {
      case dep if dep.organization == "org.scalatest" || dep.organization == "org.scalatestplus" =>
        "TestModule.ScalaTest"
      case dep if dep.organization == "org.specs2" => "TestModule.Spec2"
    }.orElse(mvnDeps.collectFirst {
      case dep if dep.organization == "org.testng" => "TestModule.TestNg"
      case dep if dep.organization == "junit" => "TestModule.Junit4"
      case dep if dep.organization == "org.junit.jupiter" => "TestModule.Junit5"
      case dep if dep.organization == "com.lihaoyi" && dep.name == "utest" => "TestModule.Utest"
      case dep if dep.organization == "org.scalameta" && dep.name == "munit" => "TestModule.Munit"
      case dep if dep.organization == "com.disneystreaming" && dep.name == "weaver-scalacheck" =>
        "TestModule.Weaver"
      case dep
          if dep.organization == "dev.zio" && (dep.name == "zio-test" || dep.name == "zio-test-sbt") =>
        "TestModule.ZioTest"
      case dep if dep.organization == "org.scalacheck" => "TestModule.ScalaCheck"
    })

  def isBomDep(organization: String, name: String): Boolean = name.endsWith("-bom") ||
    (organization == "org.springframework.boot" && name == "spring-boot-dependencies")

  def overrideArtifactName(name: String, segments: Seq[String]): String = name match {
    case "" => null
    case _ => if (name == segments.mkString("-")) null else name
  }

  def overridePomPackagingType(packaging: String): String = packaging match {
    case "" | "jar" => null
    case _ => packaging
  }
}
