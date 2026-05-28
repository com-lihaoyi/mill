package mill.androidlib

import mill.T
import mill.api.{PathRef, Task}

@mill.api.experimental
trait AndroidNativeAppModule extends AndroidAppModule {

  def androidNativeSource: T[PathRef] = Task.Source {
    moduleDir / "src/main/cpp"
  }

  /**
   * The path of each of the C/C++ source files to be compiled.
   */
  def androidExternalNativeLibs: T[Seq[PathRef]] = Task {
    os.walk(androidNativeSource().path).map(PathRef(_))
  }

  /**
   * All the different ABI's that are supported by the Android SDK.
   *
   * For more information, see [[https://developer.android.com/ndk/guides/abis]]
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
   * Extra args to pass to cmake for [[androidCompileNative]].
   *
   * For more information see [[https://developer.android.com/ndk/guides/cmake#build-command]]
   * @return
   */
  def androidCMakeExtraArgs: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /**
   * This method compiles all the native libraries using CMake and Ninja
   * and generates the .so files in the output directory for each of the supported ABI's.
   *
   * For more information see [[https://developer.android.com/ndk/guides/cmake#build-command]]
   */
  def androidCompileNative: T[PathRef] = Task {
    val outDir = Task.dest // Mill provides a dest dir for this task
    os.makeDir.all(outDir) // ensure output dir exists

    // Real absolute paths for everything handed to CMake / Ninja. On
    // reproducible-2 `.toString` returns the `../mill-workspace/...` alias
    // form; CMake fans out into helper scripts (`Modules/...`) and the NDK
    // toolchain file that all reside outside the cwd alias-parent setup, so
    // any relative path passed to `cmake -DANDROID_NDK=...` fails to resolve
    // ("CMAKE_C_COMPILER not set, after EnableLanguage").
    def abs(p: os.Path): String = p.wrapped.toAbsolutePath.normalize().toString
    val ndkAbs = abs(androidSdkModule().ndkPath())

    for (abi <- androidSupportedAbis()) {

      val outFile = outDir / "lib" / abi
      val outCxx = outDir / ".cxx"
      os.makeDir.all(outDir / abi)
      os.makeDir.all(outCxx)

      val cmakeArgs = Seq(
        abs(androidSdkModule().cmakeExe().path),
        s"-S ${abs(androidNativeSource().path)}",
        "-DCMAKE_SYSTEM_NAME=Android",
        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
        s"-DCMAKE_SYSTEM_VERSION=${androidBuildToolsVersion()}",
        s"-DANDROID_PLATFORM=android-${androidBuildToolsVersion()}",
        s"-DANDROID_ABI=${abi}",
        s"-DCMAKE_ANDROID_ARCH_ABI=${abi}",
        s"-DANDROID_NDK=${ndkAbs}",
        s"-DCMAKE_ANDROID_NDK=${ndkAbs}",
        s"-DCMAKE_TOOLCHAIN_FILE=${abs(androidSdkModule().cmakeToolchainFilePath().path)}",
        s"-DCMAKE_MAKE_PROGRAM=${abs(androidSdkModule().ninjaExe().path)}",
        s"-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${abs(outFile)}",
        s"-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${abs(outFile)}",
        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
        s"-B ${abs(outCxx)}/${abi}",
        "-GNinja"
      ) ++ androidCMakeExtraArgs()

      Task.log.info(s"Calling CMake with arguments: ${cmakeArgs.mkString(" ")}")

      os.proc(cmakeArgs).call()

      val ninjaArgs = Seq(
        abs(androidSdkModule().ninjaExe().path),
        "-C",
        s"${abs(outCxx)}/${abi}",
        s"${androidNativeLibName()}.so"
      )

      os.proc(ninjaArgs).call()
    }

    PathRef(outDir)
  }

  /**
   * Declares the native libraries (.so files) from [[androidCompileNative]]
   * to be included in the APK
   * @return
   */
  def androidPackageableNativeLibs: T[Seq[AndroidPackageableExtraFile]] = Task {
    // Include native libraries (.so files) from androidCompileNative task
    val nativeLibsDir = androidCompileNative().path
    os.walk(nativeLibsDir).filter(_.ext == "so").map {
      path =>
        AndroidPackageableExtraFile(PathRef(path), path.relativeTo(nativeLibsDir))
    }
  }

  /**
   * Include the compiled native libraries in the APK.
   */
  override def androidPackageableExtraFiles: T[Seq[AndroidPackageableExtraFile]] = Task {
    super.androidPackageableExtraFiles() ++ androidPackageableNativeLibs()

  }

}
