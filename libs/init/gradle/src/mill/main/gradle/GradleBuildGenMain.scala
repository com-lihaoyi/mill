package mill.main.gradle

import mill.main.buildgen.*
import mill.main.gradle.BuildInfo.exportpluginAssemblyResource
import mill.util.Jvm
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import pprint.Util.literalize

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.util.Using

object GradleBuildGenMain {

  def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args.toSeq)

  @mainargs.main(doc = "Generates Mill build files that are derived from a Gradle build.")
  def init(
      @mainargs.arg(doc = "Coursier JVM identifier for JDK to use to run Gradle")
      gradleJvmId: String = "system",
      @mainargs.arg(doc = "Coursier JVM identifier to assign to mill-jvm-version key in the build header")
      millJvmId: String = "system",
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag
  ): Unit = {
    println("converting Gradle build")

    val exportPluginJar = Using.resource(
      getClass.getResourceAsStream(exportpluginAssemblyResource)
    )(os.temp(_, suffix = ".jar"))
    val initScript = os.temp(
      s"""initscript {
         |    dependencies {
         |      classpath files(${literalize(exportPluginJar.toString)})
         |    }
         |}
         |rootProject {
         |    apply plugin: mill.main.gradle.BuildModelPlugin
         |}
         |""".stripMargin,
      suffix = ".gradle"
    )
    val gradleConnector = GradleConnector.newConnector match {
      case conn: DefaultGradleConnector =>
        conn.daemonMaxIdleTime(1, TimeUnit.SECONDS)
        conn
      case conn => conn
    }
    var packages =
      try Using.resource(gradleConnector.forProjectDirectory(os.pwd.toIO).connect) { connection =>
          val model = connection.model(classOf[BuildModel])
            .addArguments("--init-script", initScript.toString)
            .setJavaHome(Jvm.resolveJavaHome(gradleJvmId).get.toIO)
            .setStandardOutput(System.out).get
          upickle.default.read[Seq[PackageSpec]](model.asJson)
        }
      finally gradleConnector.disconnect()
    packages = adjustModuleDeps(packages)

    val (depNames, packages0) =
      if (noMeta.value) (Nil, packages) else BuildGen.withNamedDeps(packages)
    val (baseModule, packages1) =
      Option.when(!noMeta.value)(BuildGen.withBaseModule(packages0, "MavenTests", "MavenModule"))
        .flatten.fold((None, packages0))((base, packages) => (Some(base), packages))
    val millJvmOpts = {
      val properties = new Properties()
      val file = os.pwd / "gradle/wrapper/gradle-wrapper.properties"
      if (os.isFile(file)) Using.resource(os.read.inputStream(file))(properties.load)
      val prop = properties.getProperty("org.gradle.jvmargs")
      if (prop == null) Nil else prop.trim.split("\\s").toSeq
    }
    BuildGen.writeBuildFiles(packages1, millJvmId, merge.value, depNames, baseModule, millJvmOpts)
  }

  private def adjustModuleDeps(packages: Seq[PackageSpec]) = {
    val moduleLookup =
      packages.map(pkg => (pkg.dir.segments, pkg.module)).toMap[Seq[String], ModuleSpec]

    def adjust(module: ModuleSpec): ModuleSpec = {
      var module0 = module
      if (module.supertypes.contains("PublishModule")) {
        val (bomModuleDeps, bomModuleDepRefs) = module0.bomModuleDeps.base.partition { dep =>
          val module = moduleLookup(dep.segments)
          module.supertypes.contains("PublishModule")
        }
        if (bomModuleDepRefs.nonEmpty) {
          module0 = module0.copy(
            bomMvnDeps = module0.bomMvnDeps.copy(appendRefs = bomModuleDepRefs),
            depManagement = module0.depManagement.copy(appendRefs = bomModuleDepRefs),
            bomModuleDeps = bomModuleDeps
          )
        }
      }
      module0.copy(test = module0.test.map(adjust))
    }
    packages.map(pkg => pkg.copy(module = adjust(pkg.module)))
  }
}
