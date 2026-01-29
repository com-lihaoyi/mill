package mill.javalib

import mill.*
import mill.api.Discover
import mill.testkit.TestRootModule
import mill.util.TokenReaders.*

import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

trait AssemblyTestUtils {

  import java.io.ByteArrayOutputStream
  import scala.util.Using

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"
  val assemblyMultiResourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "assembly-multi"

  def jarMainClass(jar: JarFile): Option[String] = {
    import java.util.jar.Attributes.*
    val attrs = jar.getManifest.getMainAttributes.asScala
    attrs.get(Name.MAIN_CLASS).map(_.asInstanceOf[String])
  }

  def jarEntries(jar: JarFile): Set[String] = {
    jar.entries().asScala.map(_.getName).toSet
  }

  def readFileFromJar(jar: JarFile, name: String): String = {
    Using.resource(jar.getInputStream(jar.getEntry(name))) { is =>
      val baos = new ByteArrayOutputStream()
      os.Internals.transfer(is, baos)
      new String(baos.toByteArray)
    }
  }

  // Use slf4j as a library with config files for testing assembly rules
  val slf4jDeps = Seq(mvn"ch.qos.logback:logback-classic:1.5.10")

  object HelloJavaAkkaHttpAppend extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def mvnDeps = slf4jDeps
      override def assemblyRules = Seq(Assembly.Rule.Append("logback.xml"))
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaAkkaHttpExclude extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def mvnDeps = slf4jDeps
      override def assemblyRules = Seq(Assembly.Rule.Exclude("logback.xml"))
    }

    lazy val millDiscover = Discover[this.type]

  }

  object HelloJavaAkkaHttpAppendPattern extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def mvnDeps = slf4jDeps
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.xml"))
    }

    lazy val millDiscover = Discover[this.type]

  }

  object HelloJavaAkkaHttpExcludePattern extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def mvnDeps = slf4jDeps
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.xml"))
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaAkkaHttpRelocate extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def mvnDeps = slf4jDeps
      override def assemblyRules =
        Seq(Assembly.Rule.Relocate("ch.qos.logback.**", "shaded.logback.@1"))
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaAkkaHttpNoRules extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def mvnDeps = slf4jDeps
      override def assemblyRules = Seq()
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaMultiAppend extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaMultiExclude extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaMultiAppendPattern extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaMultiAppendByPatternWithSeparator extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf", "\n"))
    }
    object model extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaMultiExcludePattern extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaMultiNoRules extends TestRootModule {
    object core extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq()
    }
    object model extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

}
