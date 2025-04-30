package mill.androidlib

import mill.T
import mill.define.{PathRef, Task}

trait AndroidNativeAppModule extends AndroidAppModule {

  /**
   * The path of each of the C/C++ source files to be compiled.
   */
  def androidExternalNativeLibs: T[Seq[PathRef]]

  /**
   * All the different ABI's that are supported by the Android SDK.
   */
  def androidSupportedAbis: T[Seq[String]] = Task {
    Seq("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
  }

  /**
   * The version of the Android build tools to be used
   */
  def androidBuildToolsVersion: T[String] = Task {
    androidSdkModule().buildToolsVersion().split('.')(0)
  }

  /**
   * The name of the native library to be compiled.
   */
  def androidNativeLibName: T[String] = Task {
    "libnative-lib"
  }

  /**
   * This method compiles all the native libraries using CMake and Ninja
   * and generates the .so files in the output directory for each of the supported ABI's.
   */
  def androidCompileNative: T[PathRef] = Task {
    val outDir = Task.dest // Mill provides a dest dir for this task
    os.makeDir.all(outDir) // ensure output dir exists

    for (abi <- androidSupportedAbis()) {

      val outFile = outDir / "lib" / abi
      val outCxx = outDir / ".cxx"
      os.makeDir.all(outDir / abi)
      os.makeDir.all(outCxx)

      val cmakeArgs = Seq(
        s"${androidSdkModule().cmakePath().path.toString}",
        s"-H/${Task.workspace.toString}/app/src/main/cpp/",
        "-DCMAKE_SYSTEM_NAME=Android",
        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
        s"-DCMAKE_SYSTEM_VERSION=${androidBuildToolsVersion()}",
        s"-DANDROID_PLATFORM=android-${androidBuildToolsVersion()}",
        s"-DANDROID_ABI=${abi}",
        s"-DCMAKE_ANDROID_ARCH_ABI=${abi}",
        s"-DANDROID_NDK=${androidSdkModule().ndkPath().path.toString}",
        s"-DCMAKE_ANDROID_NDK=${androidSdkModule().ndkPath().path.toString}",
        s"-DCMAKE_TOOLCHAIN_FILE=${androidSdkModule().cmakeToolchainFilePath().path.toString}",
        s"-DCMAKE_MAKE_PROGRAM=${androidSdkModule().ninjaPath().path.toString}",
        s"-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outFile.toString}",
        s"-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outFile.toString}",
        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
        s"-B/${outCxx.toString}/${abi}",
        "-GNinja"
      )

      T.log.info(s"Calling CMake with arguments: ${cmakeArgs.mkString(" ")}")

      os.proc(cmakeArgs).call()

      val ninjaArgs = Seq(
        s"${androidSdkModule().ninjaPath().path.toString}",
        "-C",
        s"${outCxx.toString}/${abi}",
        s"${androidNativeLibName()}.so"
      )

      os.proc(ninjaArgs).call()
    }

    PathRef(outDir)
  }

  def androidPackageableNativeLibs: T[Seq[AndroidPackagableExtraFile]] = Task {
    // Include native libraries (.so files) from androidCompileNative task
    val nativeLibsDir = androidCompileNative().path
    os.walk(nativeLibsDir).filter(_.ext == "so").map {
      path =>
        AndroidPackagableExtraFile(PathRef(path), path.relativeTo(nativeLibsDir))
    }
  }

  /**
   * Include the compiled native libraries in the APK.
   */
  override def androidPackageableExtraFiles: T[Seq[AndroidPackagableExtraFile]] = Task {
    super.androidPackageableExtraFiles() ++ androidPackageableNativeLibs()

  }

}
