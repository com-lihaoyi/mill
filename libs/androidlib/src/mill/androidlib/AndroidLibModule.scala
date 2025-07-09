package mill.androidlib

import mill.*
import mill.api.{PathRef, Task}
import mill.javalib.*
import mill.javalib.publish.{PackagingType, PublishInfo}
import mill.util.Jvm
import upickle.default.*
import scala.xml.*

@mill.api.experimental
trait AndroidLibModule extends AndroidModule with PublishModule {

  /**
   * The package name of the module. Used in the generated AndroidManifest.xml
   * and for placing the android resources in.
   * @return
   */
  def androidLibPackage: String

  override final def androidNamespace: String = androidLibPackage

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   * TODO dynamically add android:debuggable
   */
  override def androidManifest: T[PathRef] = Task {
    val manifestFromSourcePath = moduleDir / "src/main/AndroidManifest.xml"

    val manifestElem = XML.loadFile(manifestFromSourcePath.toString())
    // add the application package
    val manifestWithPackage =
      manifestElem % Attribute(None, "package", Text(androidLibPackage), Null)

    val generatedManifestPath = Task.dest / "AndroidManifest.xml"
    os.write(generatedManifestPath, manifestWithPackage.mkString)

    PathRef(generatedManifestPath)
  }

  /**
   * The packaging type of the module. This is used to determine how the module
   * should be published. For Android libraries, this is always Aar.
   */
  override def pomPackagingType: String = PackagingType.Aar

  /**
   * Tailored to publish an AAR artifact. Throws an error if the packaging type is not
   * Aar
   * @return
   */
  override def publishArtifacts: T[PublishModule.PublishData] = {
    val baseNameTask: Task[String] = Task.Anon { s"${artifactId()}-${publishVersion()}" }
    val defaultPayloadTask: Task[Seq[(PathRef, String)]] = (pomPackagingType, this) match {
      case (PackagingType.Aar, androidLib: AndroidLibModule) => Task.Anon {
          val baseName = baseNameTask()
          Seq(
            androidLib.androidAar() -> s"$baseName.aar",
            sourceJar() -> s"$baseName-sources.jar",
            docJar() -> s"$baseName-javadoc.jar",
            pom() -> s"$baseName.pom"
          )
        }
      case (otherPackagingType, otherModuleType) =>
        throw new IllegalArgumentException(
          s"Packaging type $otherPackagingType not supported with $otherModuleType"
        )
    }
    Task {
      val baseName = baseNameTask()
      PublishModule.PublishData(
        meta = artifactMetadata(),
        payload = defaultPayloadTask() ++ extraPublish().map(p =>
          (p.file, s"$baseName${p.classifierPart}.${p.ext}")
        )
      )
    }
  }

  override def defaultPublishInfos: T[Seq[PublishInfo]] = {
    def defaultPublishJars: Task[Seq[(PathRef, PathRef => PublishInfo)]] = {
      pomPackagingType match {
        case PackagingType.Pom => Task.Anon(Seq())
        case _ => Task.Anon(Seq(
            (androidAar(), PublishInfo.aar),
            (sourceJar(), PublishInfo.sourcesJar),
            (docJar(), PublishInfo.docJar)
          ))
      }
    }
    Task {
      defaultPublishJars().map { case (jar, info) => info(jar) }
    }
  }

  def androidAar: T[PathRef] = Task {
    val dest = Task.dest
    val aarFile = dest / "library.aar"
    val compiledRes = dest / "compiled-res"
    val classesJar = dest / "classes.jar"
    val unpackedAar = dest / "unpacked-aar"

    val classFiles = compile().classes.path
    Jvm.createJar(
      jar = classesJar,
      inputPaths = Seq(classFiles)
    )

    os.makeDir.all(compiledRes)

    val compileResult = os.proc(
      androidSdkModule().aapt2Path().path,
      "compile",
      "--dir",
      androidResources(),
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
    os.unzip(
      source = tempZip,
      dest = unpackedAar
    )

    os.move(classesJar, unpackedAar / "classes.jar", replaceExisting = true)
    os.copy(
      androidMergedManifest().path,
      unpackedAar / "AndroidManifest.xml",
      replaceExisting = true
    )
    os.zip(
      dest = aarFile,
      sources = Seq(unpackedAar)
    )

    PathRef(aarFile)
  }

  trait AndroidLibTests extends JavaTests {

    override def sources: T[Seq[PathRef]] = Task.Sources("src/test/java")

  }
}
