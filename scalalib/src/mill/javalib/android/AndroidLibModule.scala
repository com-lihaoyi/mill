package mill.javalib.android

import mill.*
import mill.scalalib.*
import mill.api.PathRef
import mill.define.Task
import mill.scalalib.publish.{PackagingType, PublishInfo}
import os.RelPath
import upickle.default.*

@mill.api.experimental
trait AndroidLibModule extends AndroidModule with PublishModule {

  private val parent: AndroidLibModule = this

  //  /**
  //   * The packaging type. See [[PackagingType]] for specially handled values.
  //   */
  override def pomPackagingType: String =
    this match {
      case _: BomModule => PackagingType.Pom
      case _ => PackagingType.Aar
    }

  override def jar: T[PathRef] = {
    androidAar()
  }

  override def defaultPublishInfos: T[Seq[PublishInfo]] = {
    def defaultPublishJars: Task[Seq[(PathRef, PathRef => PublishInfo)]] = {
      pomPackagingType match {
        case PackagingType.Pom => Task.Anon(Seq())
        case _ => Task.Anon(Seq(
            (jar(), PublishInfo.aar _),
            (sourceJar(), PublishInfo.sourcesJar _),
            (docJar(), PublishInfo.docJar _)
          ))
      }
    }
    Task {
      defaultPublishJars().map { case (jar, info) => info(jar) }
    }
  }

  def androidAar: T[PathRef] = Task {
    val dest = T.dest
    val aarFile = dest / "library.aar"
    val compiledRes = dest / "compiled-res"
    val classesJar = dest / "classes.jar"
    val unpackedAar = dest / "unpacked-aar"

    val classFiles = compile().classes.path
    os.proc("jar", "cvf", classesJar.toString, "-C", classFiles.toString, ".").call()

    os.makeDir.all(compiledRes)

    val compileResult = os.proc(
      androidSdkModule().aapt2Path().path,
      "compile",
      "--dir",
      androidResources()._1.path, // typically your resource folder
      "-o",
      compiledRes.toString
    ).call()

    if (compileResult.exitCode != 0) {
      throw new RuntimeException(
        s"aapt2 failed to compile resources with error code ${compileResult.exitCode}"
      )
    }

    val linkResult = os.proc(
      androidSdkModule().aapt2Path().path,
      "link",
      "--static-lib",
      "-o",
      aarFile.toString,
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--manifest",
      androidMergedManifest().path.toString
    ).call(cwd = compiledRes)

    if (linkResult.exitCode != 0) {
      throw new RuntimeException(
        s"aapt2 failed to link resources with error code ${linkResult.exitCode}"
      )
    }

    val tempZip = Task.dest / "library.zip"
    os.move(aarFile, tempZip)
    os.proc("unzip", tempZip.toString, "-d", unpackedAar.toString).call()
    os.move(classesJar, unpackedAar / "classes.jar", replaceExisting = true)
    os.move(
      androidMergedManifest().path,
      unpackedAar / "AndroidManifest.xml",
      replaceExisting = true
    )
    os.proc("zip", "-r", aarFile.toString, ".").call(cwd = unpackedAar)

    PathRef(aarFile)
  }

  trait AndroidLibTests extends JavaTests {

    private def testPath = parent.moduleDir / "src/test"

    override def sources: T[Seq[PathRef]] = Seq(PathRef(testPath / "java"))

    override def resources: T[Seq[PathRef]] = Task.Sources(Seq(PathRef(testPath / "res")))

  }
}
