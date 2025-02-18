package mill.javalib.android

import mill.*
import mill.scalalib.*
import mill.api.{PathRef, internal}
import mill.define.Task
import mill.scalalib.bsp.BspBuildTarget
import mill.scalalib.publish.{PackagingType, PublishInfo}
import mill.testrunner.TestResult
import os.RelPath
import upickle.default.*

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

  private def aar: T[PathRef] = androidAar()

  override def jar = {
    aar
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
    os.proc(
      androidSdkModule().aapt2Path().path,
      "compile",
      "--dir",
      androidResources()._1.path, // typically your resource folder
      "-o",
      compiledRes.toString
    ).call()

    os.proc(
      androidSdkModule().aapt2Path().path,
      "link",
      "--static-lib",
      "-o",
      aarFile.toString,
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--manifest",
      androidMergedManifest().path.toString
    )
      .call(cwd = compiledRes)

    val tempZip = aarFile / os.up / "library.zip"
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

  trait AndroidLibInstrumentedTests extends AndroidLibModule with AndroidTestModule {
    private def androidMainSourcePath = parent.moduleDir
    private def androidTestPath = androidMainSourcePath / "src/androidTest"

//    override def moduleDeps: Seq[JavaModule] = Seq(parent)

    override def androidCompileSdk: T[Int] = parent.androidCompileSdk
    override def androidMinSdk: T[Int] = parent.androidMinSdk
    override def androidTargetSdk: T[Int] = parent.androidTargetSdk

    override def androidIsDebug: T[Boolean] = parent.androidIsDebug

    override def androidReleaseKeyAlias: T[Option[String]] = parent.androidReleaseKeyAlias
    override def androidReleaseKeyName: T[Option[String]] = parent.androidReleaseKeyName
    override def androidReleaseKeyPass: T[Option[String]] = parent.androidReleaseKeyPass
    override def androidReleaseKeyStorePass: T[Option[String]] = parent.androidReleaseKeyStorePass
    override def androidReleaseKeyPath: T[Option[PathRef]] = parent.androidReleaseKeyPath

    override def androidEmulatorPort: String = parent.androidEmulatorPort

    override def sources: T[Seq[PathRef]] = Seq(PathRef(androidTestPath / "java"))

    /** The resources in res directories of both main source and androidTest sources */
    override def resources: T[Seq[PathRef]] = Task.Sources {
      val libResFolders = androidUnpackArchives().flatMap(_.resources)
      libResFolders ++ Seq(PathRef(androidTestPath / "res"))
    }

    override def generatedSources: T[Seq[PathRef]] = Task.Sources(Seq.empty[PathRef])

    /* TODO on debug work, an AndroidManifest.xml with debug and instrumentation settings
     * will need to be created. Then this needs to point to the location of that debug
     * AndroidManifest.xml
     */
    override def androidManifest: Task[PathRef] = parent.androidManifest

    override def androidVirtualDeviceIdentifier: String = parent.androidVirtualDeviceIdentifier
    override def androidEmulatorArchitecture: String = parent.androidEmulatorArchitecture

    def instrumentationPackage: String

    def testFramework: T[String]

    def androidInstall: Target[String] = Task {
      val emulator = runningEmulator()
      os.call(
        (
          androidSdkModule().adbPath().path,
          "-s",
          emulator,
          "install",
          "-r",
          androidInstantApk().path
        )
      )
      emulator
    }

    override def testTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]]
    ): Task[(String, Seq[TestResult])] = Task {
      val device = androidInstall()

      val instrumentOutput = os.proc(
        (
          androidSdkModule().adbPath().path,
          "-s",
          device,
          "shell",
          "am",
          "instrument",
          "-w",
          "-r",
          s"$instrumentationPackage/${testFramework()}"
        )
      ).spawn()

      val outputReader = instrumentOutput.stdout.buffered

      val (doneMsg, results) = InstrumentationOutput.parseTestOutputStream(outputReader)(T.log)
      val res = TestModule.handleResults(doneMsg, results, T.ctx(), testReportXml())

      res

    }

    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidInstantApk: T[PathRef] = androidApk

    @internal
    override def bspBuildTarget: BspBuildTarget = super[AndroidTestModule].bspBuildTarget.copy(
      baseDirectory = Some(androidTestPath),
      canRun = false
    )

  }
}
