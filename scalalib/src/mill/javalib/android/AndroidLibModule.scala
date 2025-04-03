package mill.javalib.android

import mill.*
import mill.scalalib.*
import mill.api.PathRef
import mill.define.Task
import mill.javalib.android.AndroidModule.AndroidModuleGeneratedSources
import mill.scalalib.publish.{PackagingType, PublishInfo}
import mill.util.Jvm
import os.RelPath
import upickle.default.*

@mill.api.experimental
trait AndroidLibModule extends AndroidModule with PublishModule {

  //  /**
  //   * The packaging type. See [[PackagingType]] for specially handled values.
  //   */
  override def pomPackagingType: String =
    this match {
      case _: BomModule => PackagingType.Pom
      case _ => PackagingType.Aar
    }

  override def defaultPublishInfos: T[Seq[PublishInfo]] = {
    def defaultPublishJars: Task[Seq[(PathRef, PathRef => PublishInfo)]] = {
      pomPackagingType match {
        case PackagingType.Pom => Task.Anon(Seq())
        case _ => Task.Anon(Seq(
            (androidReleaseAar(), PublishInfo.aar),
            (sourceJar(), PublishInfo.sourcesJar),
            (docJar(), PublishInfo.docJar)
          ))
      }
    }
    Task {
      defaultPublishJars().map { case (jar, info) => info(jar) }
    }
  }

//  implicit val pathRefRW: ReadWriter[mill.api.PathRef] =
//    readwriter[String].bimap(
//      pathRef => pathRef.path.toString, // convert to a string
//      str => mill.api.PathRef(java.nio.file.Paths.get(str)) // construct a PathRef from string
//    )
//  def androidLibGeneratedSourcesFunc: T[AndroidLibModuleGeneratedSources] = Task {
//    val manifestPath = androidMergedManifest().path
////    val manifestDest = T.dest / "AndroidManifest.xml"
////    os.copy(manifestPath, manifestDest, createFolders = true)
//    AndroidLibModuleGeneratedSources(PathRef(manifestPath))
//  }


  def androidReleaseAar: T[PathRef] = Task {
    val dest = T.dest
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
      androidReleaseResources()._1.path,
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

    val libManifests = androidUnpackArchives().map(_.manifest.get)
    val mergedManifestPath = T.dest / "AndroidManifest.xml"
    // TODO put it to the dedicated worker if cost of classloading is too high
    Jvm.callProcess(
      mainClass = "com.android.manifmerger.Merger",
      mainArgs = Seq(
        "--main",
        androidManifest().path.toString(),
        "--remove-tools-declarations",
        "--property",
        s"min_sdk_version=${androidMinSdk()}",
        "--property",
        s"target_sdk_version=${androidTargetSdk()}",
        "--property",
        s"version_code=${androidVersionCode()}",
        "--property",
        s"version_name=${androidVersionName()}",
        "--out",
        mergedManifestPath.toString()
      ) ++ libManifests.flatMap(m => Seq("--libs", m.path.toString())),
      classPath = manifestMergerClasspath().map(_.path)
    )

    val tempZip = Task.dest / "library.zip"
    os.move(aarFile, tempZip)
    os.unzip(
      source = tempZip,
      dest = unpackedAar
    )

    os.move(classesJar, unpackedAar / "classes.jar", replaceExisting = true)
    os.move(
      mergedManifestPath,
      unpackedAar / "AndroidManifest.xml",
      replaceExisting = true
    )
    os.zip(
      dest = aarFile,
      sources = Seq(unpackedAar)
    )

    PathRef(aarFile)
  }

  def androidDebugdAar: T[PathRef] = Task {
    val dest = T.dest
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
      androidDebugResources()._1.path,
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

    override def resources: T[Seq[PathRef]] = Task.Sources("src/test/res")

  }
}

object AndroidLibModule {
  case class AndroidLibModuleGeneratedSources(
      mergeManifest: PathRef
  )
  object AndroidLibModuleGeneratedSources {
    implicit def resultRW: upickle.default.ReadWriter[AndroidModuleGeneratedSources] =
      upickle.default.macroRW
  }
}
