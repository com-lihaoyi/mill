package mill.scripts

import utest.*

object ScriptTests extends TestSuite {
  def tests = Tests {
    val home = os.home
    val nativeSuffix = sys.env("MILL_NATIVE_SUFFIX")

    test("provenanceHeaders") {
      val version = sys.env("MILL_EXPECTED_DEFAULT_VERSION")
      val projectUrl = sys.env("MILL_EXPECTED_PROJECT_URL")
      val downloadUrl = sys.env("MILL_EXPECTED_DOWNLOAD_URL")
      val documentationUrl = sys.env("MILL_EXPECTED_BOOTSTRAP_DOC_URL")

      val shHeader = Seq(
        "#!/usr/bin/env sh",
        "",
        s"# Mill Build Tool: $projectUrl",
        s"# Download: $downloadUrl/mill-dist-$version-mill.sh",
        s"# Documentation: $documentationUrl",
        s"# Script Version: $version",
        ""
      )
      val batHeader = Seq(
        "@echo off",
        "",
        s"rem Mill Build Tool: $projectUrl",
        s"rem Download: $downloadUrl/mill-dist-$version-mill.bat",
        s"rem Documentation: $documentationUrl",
        s"rem Script Version: $version",
        ""
      )

      val shScript = os.Path(sys.env("MILL_TEST_SH_SCRIPT"), os.pwd)
      val batScript = os.Path(sys.env("MILL_TEST_BAT_SCRIPT"), os.pwd)
      assert(os.read.lines(shScript).take(shHeader.size) == shHeader)
      assert(os.read.lines(batScript).take(batHeader.size) == batHeader)
    }

    case class VersionPaths(
        version: String,
        downloadUrl: String,
        macLinuxDownload: os.RelPath,
        windowsDownload: os.RelPath
    ) {
      def macLinuxDownloadPath: String = (home / macLinuxDownload).toString
      def windowsDownloadPath: String = (home / windowsDownload).wrapped.toString // use .toPlatformString
    }

    val versions = List[VersionPaths](
      // Older versions of Mill have their executable downloaded from Github releases
      VersionPaths(
        "0.10.0",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.0/0.10.0-assembly",
        os.rel / ".cache/mill/download/0.10.0",
        // Windows needs the executable to be downloaded with a `.bat` extension
        // so the prepended bat script can execute correctly when the file is run
        os.rel / ".cache/mill/download/0.10.0.bat"
      ),
      VersionPaths(
        "0.10.1",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.1/0.10.1-assembly",
        os.rel / ".cache/mill/download/0.10.1",
        os.rel / ".cache/mill/download/0.10.1.bat"
      ),
      VersionPaths(
        "0.10.2",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.2/0.10.2-assembly",
        os.rel / ".cache/mill/download/0.10.2",
        os.rel / ".cache/mill/download/0.10.2.bat"
      ),
      VersionPaths(
        "0.10.3",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.3/0.10.3-assembly",
        os.rel / ".cache/mill/download/0.10.3",
        os.rel / ".cache/mill/download/0.10.3.bat"
      ),
      VersionPaths(
        "0.10.4",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.4/0.10.4-assembly",
        os.rel / ".cache/mill/download/0.10.4",
        os.rel / ".cache/mill/download/0.10.4.bat"
      ),
      VersionPaths(
        "0.10.5",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.5/0.10.5-assembly",
        os.rel / ".cache/mill/download/0.10.5",
        os.rel / ".cache/mill/download/0.10.5.bat"
      ),
      VersionPaths(
        "0.10.6",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.6/0.10.6-assembly",
        os.rel / ".cache/mill/download/0.10.6",
        os.rel / ".cache/mill/download/0.10.6.bat"
      ),
      VersionPaths(
        "0.10.7",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.7/0.10.7-assembly",
        os.rel / ".cache/mill/download/0.10.7",
        os.rel / ".cache/mill/download/0.10.7.bat"
      ),
      VersionPaths(
        "0.10.8",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.8/0.10.8-assembly",
        os.rel / ".cache/mill/download/0.10.8",
        os.rel / ".cache/mill/download/0.10.8.bat"
      ),
      VersionPaths(
        "0.10.9",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.9/0.10.9-assembly",
        os.rel / ".cache/mill/download/0.10.9",
        os.rel / ".cache/mill/download/0.10.9.bat"
      ),
      VersionPaths(
        "0.10.10",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.10/0.10.10-assembly",
        os.rel / ".cache/mill/download/0.10.10",
        os.rel / ".cache/mill/download/0.10.10.bat"
      ),
      VersionPaths(
        "0.10.11",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.11/0.10.11-assembly",
        os.rel / ".cache/mill/download/0.10.11",
        os.rel / ".cache/mill/download/0.10.11.bat"
      ),
      VersionPaths(
        "0.10.12",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.12/0.10.12-assembly",
        os.rel / ".cache/mill/download/0.10.12",
        os.rel / ".cache/mill/download/0.10.12.bat"
      ),
      VersionPaths(
        "0.10.13",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.13/0.10.13-assembly",
        os.rel / ".cache/mill/download/0.10.13",
        os.rel / ".cache/mill/download/0.10.13.bat"
      ),
      VersionPaths(
        "0.10.14",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.14/0.10.14-assembly",
        os.rel / ".cache/mill/download/0.10.14",
        os.rel / ".cache/mill/download/0.10.14.bat"
      ),
      VersionPaths(
        "0.10.15",
        "https://github.com/com-lihaoyi/mill/releases/download/0.10.15/0.10.15-assembly",
        os.rel / ".cache/mill/download/0.10.15",
        os.rel / ".cache/mill/download/0.10.15.bat"
      ),
      // Since 0.11.0, Mill executables are downloaded from Maven Central as an assembly jar
      VersionPaths(
        "0.11.0",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.0/mill-dist-0.11.0.jar",
        os.rel / ".cache/mill/download/0.11.0",
        os.rel / ".cache/mill/download/0.11.0.bat"
      ),
      VersionPaths(
        "0.11.1",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.1/mill-dist-0.11.1.jar",
        os.rel / ".cache/mill/download/0.11.1",
        os.rel / ".cache/mill/download/0.11.1.bat"
      ),
      VersionPaths(
        "0.11.2",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.2/mill-dist-0.11.2.jar",
        os.rel / ".cache/mill/download/0.11.2",
        os.rel / ".cache/mill/download/0.11.2.bat"
      ),
      VersionPaths(
        "0.11.3",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.3/mill-dist-0.11.3.jar",
        os.rel / ".cache/mill/download/0.11.3",
        os.rel / ".cache/mill/download/0.11.3.bat"
      ),
      VersionPaths(
        "0.11.4",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.4/mill-dist-0.11.4.jar",
        os.rel / ".cache/mill/download/0.11.4",
        os.rel / ".cache/mill/download/0.11.4.bat"
      ),
      VersionPaths(
        "0.11.5",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.5/mill-dist-0.11.5.jar",
        os.rel / ".cache/mill/download/0.11.5",
        os.rel / ".cache/mill/download/0.11.5.bat"
      ),
      VersionPaths(
        "0.11.6",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.6/mill-dist-0.11.6.jar",
        os.rel / ".cache/mill/download/0.11.6",
        os.rel / ".cache/mill/download/0.11.6.bat"
      ),
      VersionPaths(
        "0.11.7",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.7/mill-dist-0.11.7.jar",
        os.rel / ".cache/mill/download/0.11.7",
        os.rel / ".cache/mill/download/0.11.7.bat"
      ),
      VersionPaths(
        "0.11.8",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.8/mill-dist-0.11.8.jar",
        os.rel / ".cache/mill/download/0.11.8",
        os.rel / ".cache/mill/download/0.11.8.bat"
      ),
      VersionPaths(
        "0.11.9",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.9/mill-dist-0.11.9.jar",
        os.rel / ".cache/mill/download/0.11.9",
        os.rel / ".cache/mill/download/0.11.9.bat"
      ),
      VersionPaths(
        "0.11.10",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.10/mill-dist-0.11.10.jar",
        os.rel / ".cache/mill/download/0.11.10",
        os.rel / ".cache/mill/download/0.11.10.bat"
      ),
      VersionPaths(
        "0.11.11",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.11/mill-dist-0.11.11.jar",
        os.rel / ".cache/mill/download/0.11.11",
        os.rel / ".cache/mill/download/0.11.11.bat"
      ),
      VersionPaths(
        "0.11.12",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.12/mill-dist-0.11.12.jar",
        os.rel / ".cache/mill/download/0.11.12",
        os.rel / ".cache/mill/download/0.11.12.bat"
      ),
      VersionPaths(
        "0.11.13",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.11.13/mill-dist-0.11.13.jar",
        os.rel / ".cache/mill/download/0.11.13",
        os.rel / ".cache/mill/download/0.11.13.bat"
      ),
      VersionPaths(
        "0.12.1",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.1/mill-dist-0.12.1.jar",
        os.rel / ".cache/mill/download/0.12.1",
        os.rel / ".cache/mill/download/0.12.1.bat"
      ),
      VersionPaths(
        "0.12.10",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.10/mill-dist-0.12.10.jar",
        os.rel / ".cache/mill/download/0.12.10",
        os.rel / ".cache/mill/download/0.12.10.bat"
      ),
      VersionPaths(
        "0.12.11",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill-dist-0.12.11.jar",
        os.rel / ".cache/mill/download/0.12.11",
        os.rel / ".cache/mill/download/0.12.11.bat"
      ),
      VersionPaths(
        "0.12.2",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.2/mill-dist-0.12.2.jar",
        os.rel / ".cache/mill/download/0.12.2",
        os.rel / ".cache/mill/download/0.12.2.bat"
      ),
      VersionPaths(
        "0.12.3",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.3/mill-dist-0.12.3.jar",
        os.rel / ".cache/mill/download/0.12.3",
        os.rel / ".cache/mill/download/0.12.3.bat"
      ),
      VersionPaths(
        "0.12.4",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.4/mill-dist-0.12.4.jar",
        os.rel / ".cache/mill/download/0.12.4",
        os.rel / ".cache/mill/download/0.12.4.bat"
      ),
      VersionPaths(
        "0.12.5",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.5/mill-dist-0.12.5.jar",
        os.rel / ".cache/mill/download/0.12.5",
        os.rel / ".cache/mill/download/0.12.5.bat"
      ),
      // Since Mill 0.12.6, adding the `-native` suffix to the MILL_VERSION downloads the
      // OS-specific graal native image binary instead of the assembly jar
      VersionPaths(
        "0.12.6",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.6/mill-dist-0.12.6.jar",
        os.rel / ".cache/mill/download/0.12.6",
        os.rel / ".cache/mill/download/0.12.6.bat"
      ),
      // Make sure the `nativeSuffix` the bash script appends to the URL matches the
      // `nativeSuffix` computed by Mill's own `dist/package.mill` when uploading the artifacts,
      // and that we preserve the `-native-$nativeSuffix` suffix when saving the file to disk
      VersionPaths(
        "0.12.6-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/0.12.6/mill-dist-native-$nativeSuffix-0.12.6.jar",
        os.rel / ".cache/mill/download" / s"0.12.6-native-$nativeSuffix",
        // Graal native executables on windows need a `.exe` extension to run properly
        os.rel / ".cache/mill/download/0.12.6-native.exe"
      ),
      VersionPaths(
        "0.12.7",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.7/mill-dist-0.12.7.jar",
        os.rel / ".cache/mill/download/0.12.7",
        os.rel / ".cache/mill/download/0.12.7.bat"
      ),
      VersionPaths(
        "0.12.8",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.8/mill-dist-0.12.8.jar",
        os.rel / ".cache/mill/download/0.12.8",
        os.rel / ".cache/mill/download/0.12.8.bat"
      ),
      VersionPaths(
        "0.12.9",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.9/mill-dist-0.12.9.jar",
        os.rel / ".cache/mill/download/0.12.9",
        os.rel / ".cache/mill/download/0.12.9.bat"
      ),
      VersionPaths(
        "0.12.10",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.10/mill-dist-0.12.10.jar",
        os.rel / ".cache/mill/download/0.12.10",
        os.rel / ".cache/mill/download/0.12.10.bat"
      ),
      VersionPaths(
        "0.12.11",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill-dist-0.12.11.jar",
        os.rel / ".cache/mill/download/0.12.11",
        os.rel / ".cache/mill/download/0.12.11.bat"
      ),
      VersionPaths(
        "0.12.11-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/0.12.11/mill-dist-native-$nativeSuffix-0.12.11.jar",
        os.rel / ".cache/mill/download" / s"0.12.11-native-$nativeSuffix",
        os.rel / ".cache/mill/download/0.12.11-native.exe"
      ),
      VersionPaths(
        "0.12.11-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill-dist-0.12.11.jar",
        os.rel / ".cache/mill/download/0.12.11",
        os.rel / ".cache/mill/download/0.12.11-jvm.bat"
      ),
      // Since 0.12.12, publishing was moved from OSSRH to Sonatype Central, which was stricter
      // about what was allowed to be uploaded under a `.jar` suffix and so our assembly jar
      // and graal native binary launchers had their suffixes changed from `.jar` to `.exe`
      VersionPaths(
        "0.12.12",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.12/mill-dist-0.12.12.exe",
        os.rel / ".cache/mill/download/0.12.12",
        os.rel / ".cache/mill/download/0.12.12.bat"
      ),
      VersionPaths(
        "0.12.12-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/0.12.12/mill-dist-native-$nativeSuffix-0.12.12.exe",
        os.rel / ".cache/mill/download" / s"0.12.12-native-$nativeSuffix",
        os.rel / ".cache/mill/download/0.12.12-native.exe"
      ),
      // Since Mill 1.0.0, the native binary is the default, with the `-jvm` suffix used to
      // request the JVM assembly jar launcher.
      VersionPaths(
        "1.0.0-M1",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.0.0-M1/mill-dist-native-$nativeSuffix-1.0.0-M1.exe",
        os.rel / ".cache/mill/download" / s"1.0.0-M1-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.0.0-M1.exe"
      ),
      VersionPaths(
        "1.0.0-M1-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.0.0-M1/mill-dist-native-$nativeSuffix-1.0.0-M1.exe",
        os.rel / ".cache/mill/download" / s"1.0.0-M1-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.0.0-M1-native.exe"
      ),
      VersionPaths(
        "1.0.0-M1-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.0.0-M1/mill-dist-1.0.0-M1.exe",
        os.rel / ".cache/mill/download/1.0.0-M1",
        os.rel / ".cache/mill/download/1.0.0-M1-jvm.bat"
      ),
      VersionPaths(
        "1.0.0",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.0.0/mill-dist-native-$nativeSuffix-1.0.0.exe",
        os.rel / ".cache/mill/download" / s"1.0.0-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.0.0.exe"
      ),
      VersionPaths(
        "1.0.0-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.0.0/mill-dist-native-$nativeSuffix-1.0.0.exe",
        os.rel / ".cache/mill/download" / s"1.0.0-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.0.0-native.exe"
      ),
      VersionPaths(
        "1.0.0-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.0.0/mill-dist-1.0.0.exe",
        os.rel / ".cache/mill/download/1.0.0",
        os.rel / ".cache/mill/download/1.0.0-jvm.bat"
      ),
      VersionPaths(
        "1.1.0",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.1.0/mill-dist-native-$nativeSuffix-1.1.0.exe",
        os.rel / ".cache/mill/download" / s"1.1.0-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.1.0.exe"
      ),
      VersionPaths(
        "1.1.0-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.1.0/mill-dist-native-$nativeSuffix-1.1.0.exe",
        os.rel / ".cache/mill/download" / s"1.1.0-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.1.0-native.exe"
      ),
      VersionPaths(
        "1.1.0-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0.exe",
        os.rel / ".cache/mill/download/1.1.0",
        os.rel / ".cache/mill/download/1.1.0-jvm.bat"
      ),
      VersionPaths(
        "1.3.0",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.3.0/mill-dist-native-$nativeSuffix-1.3.0.exe",
        os.rel / ".cache/mill/download" / s"1.3.0-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.3.0.exe"
      ),
      VersionPaths(
        "1.3.0-native",
        s"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-$nativeSuffix/1.3.0/mill-dist-native-$nativeSuffix-1.3.0.exe",
        os.rel / ".cache/mill/download" / s"1.3.0-native-$nativeSuffix",
        os.rel / ".cache/mill/download/1.3.0-native.exe"
      ),
      VersionPaths(
        "1.3.0-jvm",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.3.0/mill-dist-1.3.0.exe",
        os.rel / ".cache/mill/download/1.3.0",
        os.rel / ".cache/mill/download/1.3.0-jvm.bat"
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

        assert(downloadUrl == versionPaths.downloadUrl)
        if (scala.util.Properties.isWin) {
          assert(downloadDest == versionPaths.windowsDownloadPath)
        } else {
          assert(downloadDest == versionPaths.macLinuxDownloadPath)
        }
      }
    }
  }
}
