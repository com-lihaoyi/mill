package mill.scripts

import utest._

object ScriptTests extends TestSuite {
  def tests = Tests {
    val home = os.home
    val nativeSuffix = sys.env("MILL_NATIVE_SUFFIX")
    case class VersionPaths(
        version: String,
        downloadUrl: String,
        macLinuxDownloadPath: String,
        windowsDownloadPath: String
    )

    val versions = List[VersionPaths](
      // Older versions of Mill have their executable downloaded from Github releases
      VersionPaths(
        "0.10.0",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.0/0.10.0-assembly",
        s"$home/.cache/mill/download/0.10.0",
        // Windows needs the executable to be downloaded with a `.bat` extension
        // so the prepended bat script can execute correctly when the file is run
        s"$home\\.cache\\mill\\download\\0.10.0.bat"
      ),
      VersionPaths(
        "0.10.1",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.1/0.10.1-assembly",
        s"$home/.cache/mill/download/0.10.1",
        s"$home\\.cache\\mill\\download\\0.10.1.bat"
      ),
      VersionPaths(
        "0.10.2",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.2/0.10.2-assembly",
        s"$home/.cache/mill/download/0.10.2",
        s"$home\\.cache\\mill\\download\\0.10.2.bat"
      ),
      VersionPaths(
        "0.10.3",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.3/0.10.3-assembly",
        s"$home/.cache/mill/download/0.10.3",
        s"$home\\.cache\\mill\\download\\0.10.3.bat"
      ),
      VersionPaths(
        "0.10.4",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.4/0.10.4-assembly",
        s"$home/.cache/mill/download/0.10.4",
        s"$home\\.cache\\mill\\download\\0.10.4.bat"
      ),
      VersionPaths(
        "0.10.5",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.5/0.10.5-assembly",
        s"$home/.cache/mill/download/0.10.5",
        s"$home\\.cache\\mill\\download\\0.10.5.bat"
      ),
      VersionPaths(
        "0.10.6",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.6/0.10.6-assembly",
        s"$home/.cache/mill/download/0.10.6",
        s"$home\\.cache\\mill\\download\\0.10.6.bat"
      ),
      VersionPaths(
        "0.10.7",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.7/0.10.7-assembly",
        s"$home/.cache/mill/download/0.10.7",
        s"$home\\.cache\\mill\\download\\0.10.7.bat"
      ),
      VersionPaths(
        "0.10.8",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.8/0.10.8-assembly",
        s"$home/.cache/mill/download/0.10.8",
        s"$home\\.cache\\mill\\download\\0.10.8.bat"
      ),
      VersionPaths(
        "0.10.9",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.9/0.10.9-assembly",
        s"$home/.cache/mill/download/0.10.9",
        s"$home\\.cache\\mill\\download\\0.10.9.bat"
      ),
      VersionPaths(
        "0.10.10",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.10/0.10.10-assembly",
        s"$home/.cache/mill/download/0.10.10",
        s"$home\\.cache\\mill\\download\\0.10.10.bat"
      ),
      VersionPaths(
        "0.10.11",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.11/0.10.11-assembly",
        s"$home/.cache/mill/download/0.10.11",
        s"$home\\.cache\\mill\\download\\0.10.11.bat"
      ),
      VersionPaths(
        "0.10.12",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.12/0.10.12-assembly",
        s"$home/.cache/mill/download/0.10.12",
        s"$home\\.cache\\mill\\download\\0.10.12.bat"
      ),
      VersionPaths(
        "0.10.13",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.13/0.10.13-assembly",
        s"$home/.cache/mill/download/0.10.13",
        s"$home\\.cache\\mill\\download\\0.10.13.bat"
      ),
      VersionPaths(
        "0.10.14",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.14/0.10.14-assembly",
        s"$home/.cache/mill/download/0.10.14",
        s"$home\\.cache\\mill\\download\\0.10.14.bat"
      ),
      VersionPaths(
        "0.10.15",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.15/0.10.15-assembly",
        s"$home/.cache/mill/download/0.10.15",
        s"$home\\.cache\\mill\\download\\0.10.15.bat"
      ),
      // Since 0.11.0, Mill executables are downloaded from Maven Central as an assembly jar
      VersionPaths(
        "0.11.0",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.0/mill-dist-0.11.0.jar",
        s"$home/.cache/mill/download/0.11.0",
        s"$home\\.cache\\mill\\download\\0.11.0.bat"
      ),
      VersionPaths(
        "0.11.1",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.1/mill-dist-0.11.1.jar",
        s"$home/.cache/mill/download/0.11.1",
        s"$home\\.cache\\mill\\download\\0.11.1.bat"
      ),
      VersionPaths(
        "0.11.2",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.2/mill-dist-0.11.2.jar",
        s"$home/.cache/mill/download/0.11.2",
        s"$home\\.cache\\mill\\download\\0.11.2.bat"
      ),
      VersionPaths(
        "0.11.3",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.3/mill-dist-0.11.3.jar",
        s"$home/.cache/mill/download/0.11.3",
        s"$home\\.cache\\mill\\download\\0.11.3.bat"
      ),
      VersionPaths(
        "0.11.4",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.4/mill-dist-0.11.4.jar",
        s"$home/.cache/mill/download/0.11.4",
        s"$home\\.cache\\mill\\download\\0.11.4.bat"
      ),
      VersionPaths(
        "0.11.5",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.5/mill-dist-0.11.5.jar",
        s"$home/.cache/mill/download/0.11.5",
        s"$home\\.cache\\mill\\download\\0.11.5.bat"
      ),
      VersionPaths(
        "0.11.6",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.6/mill-dist-0.11.6.jar",
        s"$home/.cache/mill/download/0.11.6",
        s"$home\\.cache\\mill\\download\\0.11.6.bat"
      ),
      VersionPaths(
        "0.11.7",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.7/mill-dist-0.11.7.jar",
        s"$home/.cache/mill/download/0.11.7",
        s"$home\\.cache\\mill\\download\\0.11.7.bat"
      ),
      VersionPaths(
        "0.11.8",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.8/mill-dist-0.11.8.jar",
        s"$home/.cache/mill/download/0.11.8",
        s"$home\\.cache\\mill\\download\\0.11.8.bat"
      ),
      VersionPaths(
        "0.11.9",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.9/mill-dist-0.11.9.jar",
        s"$home/.cache/mill/download/0.11.9",
        s"$home\\.cache\\mill\\download\\0.11.9.bat"
      ),
      VersionPaths(
        "0.11.10",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.10/mill-dist-0.11.10.jar",
        s"$home/.cache/mill/download/0.11.10",
        s"$home\\.cache\\mill\\download\\0.11.10.bat"
      ),
      VersionPaths(
        "0.11.11",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.11/mill-dist-0.11.11.jar",
        s"$home/.cache/mill/download/0.11.11",
        s"$home\\.cache\\mill\\download\\0.11.11.bat"
      ),
      VersionPaths(
        "0.11.12",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.12/mill-dist-0.11.12.jar",
        s"$home/.cache/mill/download/0.11.12",
        s"$home\\.cache\\mill\\download\\0.11.12.bat"
      ),
      VersionPaths(
        "0.11.13",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.13/mill-dist-0.11.13.jar",
        s"$home/.cache/mill/download/0.11.13",
        s"$home\\.cache\\mill\\download\\0.11.13.bat"
      ),
      VersionPaths(
        "0.12.1",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.1/mill-dist-0.12.1.jar",
        s"$home/.cache/mill/download/0.12.1",
        s"$home\\.cache\\mill\\download\\0.12.1.bat"
      ),
      VersionPaths(
        "0.12.10",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.10/mill-dist-0.12.10.jar",
        s"$home/.cache/mill/download/0.12.10",
        s"$home\\.cache\\mill\\download\\0.12.10.bat"
      ),
      VersionPaths(
        "0.12.11",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill-dist-0.12.11.jar",
        s"$home/.cache/mill/download/0.12.11",
        s"$home\\.cache\\mill\\download\\0.12.11.bat"
      ),
      VersionPaths(
        "0.12.2",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.2/mill-dist-0.12.2.jar",
        s"$home/.cache/mill/download/0.12.2",
        s"$home\\.cache\\mill\\download\\0.12.2.bat"
      ),
      VersionPaths(
        "0.12.3",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.3/mill-dist-0.12.3.jar",
        s"$home/.cache/mill/download/0.12.3",
        s"$home\\.cache\\mill\\download\\0.12.3.bat"
      ),
      VersionPaths(
        "0.12.4",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.4/mill-dist-0.12.4.jar",
        s"$home/.cache/mill/download/0.12.4",
        s"$home\\.cache\\mill\\download\\0.12.4.bat"
      ),
      VersionPaths(
        "0.12.5",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.5/mill-dist-0.12.5.jar",
        s"$home/.cache/mill/download/0.12.5",
        s"$home\\.cache\\mill\\download\\0.12.5.bat"
      ),
      // Since Mill 0.12.6, adding the `-native` suffix to the MILL_VERSION downloads the
      // OS-specific graal native image binary instead of the assembly jar
      VersionPaths(
        "0.12.6",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.6/mill-dist-0.12.6.jar",
        s"$home/.cache/mill/download/0.12.6",
        s"$home\\.cache\\mill\\download\\0.12.6.bat"
      ),
      // Make sure the `nativeSuffix` the bash script appends to the URL matches the
      // `nativeSuffix` computed by Mill's own `dist/package.mill` when uploading the artifacts,
      // and that we preserve the `-native-$nativeSuffix` suffix when saving the file to disk
      VersionPaths(
        "0.12.6-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/0.12.6/mill-dist-native-$nativeSuffix-0.12.6.jar",
        s"$home/.cache/mill/download/0.12.6-native-$nativeSuffix",
        // Graal native executables on windows need a `.exe` extension to run properly
        s"$home\\.cache\\mill\\download\\0.12.6-native.exe"
      ),
      VersionPaths(
        "0.12.7",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.7/mill-dist-0.12.7.jar",
        s"$home/.cache/mill/download/0.12.7",
        s"$home\\.cache\\mill\\download\\0.12.7.bat"
      ),
      VersionPaths(
        "0.12.8",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.8/mill-dist-0.12.8.jar",
        s"$home/.cache/mill/download/0.12.8",
        s"$home\\.cache\\mill\\download\\0.12.8.bat"
      ),
      VersionPaths(
        "0.12.9",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.9/mill-dist-0.12.9.jar",
        s"$home/.cache/mill/download/0.12.9",
        s"$home\\.cache\\mill\\download\\0.12.9.bat"
      ),
      VersionPaths(
        "0.12.10",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.10/mill-dist-0.12.10.jar",
        s"$home/.cache/mill/download/0.12.10",
        s"$home\\.cache\\mill\\download\\0.12.10.bat"
      ),
      VersionPaths(
        "0.12.11",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill-dist-0.12.11.jar",
        s"$home/.cache/mill/download/0.12.11",
        s"$home\\.cache\\mill\\download\\0.12.11.bat"
      ),
      VersionPaths(
        "0.12.11-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/0.12.11/mill-dist-native-$nativeSuffix-0.12.11.jar",
        s"$home/.cache/mill/download/0.12.11-native-$nativeSuffix",
        s"$home\\.cache\\mill\\download\\0.12.11-native.exe"
      ),
      VersionPaths(
        "0.12.11-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill-dist-0.12.11.jar",
        s"$home/.cache/mill/download/0.12.11",
        s"$home\\.cache\\mill\\download\\0.12.11-jvm.bat"
      ),
      // Since 0.12.12, publishing was moved from OSSRH to Sonatype Central, which was stricter
      // about what was allowed to be uploaded under a `.jar` suffix and so our assembly jar
      // and graal native binary launchers had their suffixes changed from `.jar` to `.exe`
      VersionPaths(
        "0.12.12",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.12/mill-dist-0.12.12.exe",
        s"$home/.cache/mill/download/0.12.12",
        s"$home\\.cache\\mill\\download\\0.12.12.bat"
      ),
      VersionPaths(
        "0.12.12-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/0.12.12/mill-dist-native-$nativeSuffix-0.12.12.exe",
        s"$home/.cache/mill/download/0.12.12-native-$nativeSuffix",
        s"$home\\.cache\\mill\\download\\0.12.12-native.exe"
      ),
      // Since Mill 1.0.0, the native binary is the default, with the `-jvm` suffix used to
      // request the JVM assembly jar launcher.
      VersionPaths(
        "1.0.0-M1",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.0.0-M1/mill-dist-native-$nativeSuffix-1.0.0-M1.exe",
        s"$home/.cache/mill/download/1.0.0-M1-native-$nativeSuffix",
        s"$home\\.cache\\mill\\download\\1.0.0-M1.exe"
      ),
      VersionPaths(
        "1.0.0-M1-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.0.0-M1/mill-dist-native-$nativeSuffix-1.0.0-M1.exe",
        s"$home/.cache/mill/download/1.0.0-M1-native-$nativeSuffix",
        s"$home\\.cache\\mill\\download\\1.0.0-M1-native.exe"
      ),
      VersionPaths(
        "1.0.0-M1-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.0.0-M1/mill-dist-1.0.0-M1.exe",
        s"$home/.cache/mill/download/1.0.0-M1",
        s"$home\\.cache\\mill\\download\\1.0.0-M1-jvm.bat"
      )
    )
    test {
      for (versionPaths <- versions) {
        val res = os.call(
          cmd = millCmd,
          env = Map(
            "MILL_VERSION" -> versionPaths.version,
            "MILL_TEST_DRY_RUN_LAUNCHER_SCRIPT" -> "1"
          )
        )

        val Seq(downloadUrl, downloadDest) = res.out.lines()
        println("downloadUrl: " + downloadUrl)
        println("downloadDest: " + downloadDest)
        assert(downloadUrl == versionPaths.downloadUrl)
        if (!scala.util.Properties.isWin) assert(downloadDest == versionPaths.macLinuxDownloadPath)
        else assert(downloadDest == versionPaths.windowsDownloadPath)
      }
    }
  }

}
