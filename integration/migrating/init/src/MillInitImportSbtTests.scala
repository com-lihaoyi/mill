package mill.integration

import mill.integration.IntegrationTesterUtil.*
import mill.javalib.publish.*
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitImportSbtTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("Airstream") - integrationTestGitRepo(
      "https://github.com/raquo/Airstream.git",
      "v17.2.1"
    ) { tester =>
      import tester.*

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val showModuleDeps = eval("__.showModuleDeps").out.linesIterator.toSeq
      assertGoldenLiteral(
        showModuleDeps,
        List(
          "Module dependencies of [2.13.16]:",
          "Module dependencies of [2.13.16].test:",
          "  [2.13.16]",
          "Module dependencies of [3.3.3]:",
          "Module dependencies of [3.3.3].test:",
          "  [3.3.3]"
        )
      )

      val repositories = showNamedRepositories(tester)
      assertGoldenLiteral(
        repositories,
        Map(
          "[2.13.16].repositories" -> List(
            "https://oss.sonatype.org/content/repositories/public",
            "https://s01.oss.sonatype.org/content/repositories/public"
          ),
          "[2.13.16].test.repositories" -> List(),
          "[3.3.3].repositories" -> List(
            "https://oss.sonatype.org/content/repositories/public",
            "https://s01.oss.sonatype.org/content/repositories/public"
          ),
          "[3.3.3].test.repositories" -> List()
        )
      )

      val jvmId = showNamedJvmId(tester)
      assertGoldenLiteral(jvmId, Map("[2.13.16].jvmId" -> "", "[3.3.3].jvmId" -> ""))

      val mvnDeps = showNamedMvnDeps(tester)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "[2.13.16].mvnDeps" -> List(
            "org.scala-js::scalajs-dom::2.8.0",
            "app.tulz::tuplez-full-light::0.4.0",
            "com.raquo::ew::0.2.0"
          ),
          "[2.13.16].test.mvnDeps" -> List("org.scalatest::scalatest::3.2.14"),
          "[3.3.3].mvnDeps" -> List(
            "org.scala-js::scalajs-dom::2.8.0",
            "app.tulz::tuplez-full-light::0.4.0",
            "com.raquo::ew::0.2.0"
          ),
          "[3.3.3].test.mvnDeps" -> List("org.scalatest::scalatest::3.2.14")
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester)
      assertGoldenLiteral(
        compileMvnDeps,
        Map(
          "[2.13.16].compileMvnDeps" -> List(),
          "[2.13.16].test.compileMvnDeps" -> List(),
          "[3.3.3].compileMvnDeps" -> List(),
          "[3.3.3].test.compileMvnDeps" -> List()
        )
      )
      val runMvnDeps = showNamedRunMvnDeps(tester)
      assertGoldenLiteral(
        runMvnDeps,
        Map(
          "[2.13.16].runMvnDeps" -> List(),
          "[2.13.16].test.runMvnDeps" -> List(),
          "[3.3.3].runMvnDeps" -> List(),
          "[3.3.3].test.runMvnDeps" -> List()
        )
      )
      val bomMvnDeps = showNamedBomMvnDeps(tester)
      assertGoldenLiteral(
        bomMvnDeps,
        Map(
          "[2.13.16].bomMvnDeps" -> List(),
          "[2.13.16].test.bomMvnDeps" -> List(),
          "[3.3.3].bomMvnDeps" -> List(),
          "[3.3.3].test.bomMvnDeps" -> List()
        )
      )
      val javacOptions = showNamedJavacOptions(tester)
      assertGoldenLiteral(
        javacOptions,
        Map(
          "[2.13.16].javacOptions" -> List(),
          "[2.13.16].test.javacOptions" -> List(),
          "[3.3.3].javacOptions" -> List(),
          "[3.3.3].test.javacOptions" -> List()
        )
      )

      val pomParentProject = showNamedPomParentProject(tester)
      assertGoldenLiteral(
        pomParentProject,
        Map("[2.13.16].pomParentProject" -> None, "[3.3.3].pomParentProject" -> None)
      )
      val pomSettings = showNamedPomSettings(tester)
      assertGoldenLiteral(
        pomSettings,
        Map(
          "[2.13.16].pomSettings" -> PomSettings(
            description = "Airstream",
            organization = "com.raquo",
            url = "https://github.com/raquo/Airstream",
            licenses = List(
              License(
                id = "",
                name = "MIT",
                url = "https://github.com/raquo/Airstream/blob/master/LICENSE.md",
                isOsiApproved = false,
                isFsfLibre = false,
                distribution = "repo"
              )
            ),
            versionControl = VersionControl(
              browsableRepository = Some("https://github.com/raquo/Airstream"),
              connection = Some("scm:git@github.com/raquo/Airstream.git"),
              developerConnection = None,
              tag = None
            ),
            developers = List(
              Developer(
                id = "raquo",
                name = "Nikita Gazarov",
                url = "https://github.com/raquo",
                organization = None,
                organizationUrl = None
              )
            ),
            packaging = "jar"
          ),
          "[3.3.3].pomSettings" -> PomSettings(
            description = "Airstream",
            organization = "com.raquo",
            url = "https://github.com/raquo/Airstream",
            licenses = List(
              License(
                id = "",
                name = "MIT",
                url = "https://github.com/raquo/Airstream/blob/master/LICENSE.md",
                isOsiApproved = false,
                isFsfLibre = false,
                distribution = "repo"
              )
            ),
            versionControl = VersionControl(
              browsableRepository = Some("https://github.com/raquo/Airstream"),
              connection = Some("scm:git@github.com/raquo/Airstream.git"),
              developerConnection = None,
              tag = None
            ),
            developers = List(
              Developer(
                id = "raquo",
                name = "Nikita Gazarov",
                url = "https://github.com/raquo",
                organization = None,
                organizationUrl = None
              )
            ),
            packaging = "jar"
          )
        )
      )
      val publishVersion = showNamedPublishVersion(tester)
      assertGoldenLiteral(
        publishVersion,
        Map("[2.13.16].publishVersion" -> "17.2.1", "[3.3.3].publishVersion" -> "17.2.1")
      )
      val versionScheme = showNamedVersionScheme(tester)
      assertGoldenLiteral(
        versionScheme,
        Map("[2.13.16].versionScheme" -> None, "[3.3.3].versionScheme" -> None)
      )

      val compileScala3Res = tester.eval("[3.3.3].compile")
      assert(
        compileScala3Res.isSuccess,
        compileScala3Res.err.contains("compiling 144 Scala sources to")
      )

      val testScala2Res = tester.eval("[2.13.16].test")
      assert(
        !testScala2Res.isSuccess,
        testScala2Res.err.contains(
          "scala.scalajs.js.JavaScriptException: ReferenceError: window is not defined"
        )
      )
    }
    test("cats") - integrationTestGitRepo(
      "https://github.com/typelevel/cats.git",
      "v2.13.0"
    ) { tester =>
      import tester.*

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val showModuleDeps = eval("__.showModuleDeps").out.linesIterator.toSeq
      assertGoldenLiteral(
        showModuleDeps,
        List(
          "Module dependencies of algebra-core.js[2.12.20]:",
          "  kernel.js[2.12.20]",
          "Module dependencies of algebra-core.js[2.12.20].test:",
          "  algebra-core.js[2.12.20]",
          "Module dependencies of algebra-core.js[2.13.16]:",
          "  kernel.js[2.13.16]",
          "Module dependencies of algebra-core.js[2.13.16].test:",
          "  algebra-core.js[2.13.16]",
          "Module dependencies of algebra-core.js[3.3.4]:",
          "  kernel.js[3.3.4]",
          "Module dependencies of algebra-core.js[3.3.4].test:",
          "  algebra-core.js[3.3.4]",
          "Module dependencies of algebra-core.jvm[2.12.20]:",
          "  kernel.jvm[2.12.20]",
          "Module dependencies of algebra-core.jvm[2.12.20].test:",
          "  algebra-core.jvm[2.12.20]",
          "Module dependencies of algebra-core.jvm[2.13.16]:",
          "  kernel.jvm[2.13.16]",
          "Module dependencies of algebra-core.jvm[2.13.16].test:",
          "  algebra-core.jvm[2.13.16]",
          "Module dependencies of algebra-core.jvm[3.3.4]:",
          "  kernel.jvm[3.3.4]",
          "Module dependencies of algebra-core.jvm[3.3.4].test:",
          "  algebra-core.jvm[3.3.4]",
          "Module dependencies of algebra-core.native[2.12.20]:",
          "  kernel.native[2.12.20]",
          "Module dependencies of algebra-core.native[2.12.20].test:",
          "  algebra-core.native[2.12.20]",
          "Module dependencies of algebra-core.native[2.13.16]:",
          "  kernel.native[2.13.16]",
          "Module dependencies of algebra-core.native[2.13.16].test:",
          "  algebra-core.native[2.13.16]",
          "Module dependencies of algebra-core.native[3.3.4]:",
          "  kernel.native[3.3.4]",
          "Module dependencies of algebra-core.native[3.3.4].test:",
          "  algebra-core.native[3.3.4]",
          "Module dependencies of algebra-laws.js[2.12.20]:",
          "  kernel-laws.js[2.12.20]",
          "  algebra-core.js[2.12.20]",
          "Module dependencies of algebra-laws.js[2.12.20].test:",
          "  algebra-laws.js[2.12.20]",
          "Module dependencies of algebra-laws.js[2.13.16]:",
          "  kernel-laws.js[2.13.16]",
          "  algebra-core.js[2.13.16]",
          "Module dependencies of algebra-laws.js[2.13.16].test:",
          "  algebra-laws.js[2.13.16]",
          "Module dependencies of algebra-laws.js[3.3.4]:",
          "  kernel-laws.js[3.3.4]",
          "  algebra-core.js[3.3.4]",
          "Module dependencies of algebra-laws.js[3.3.4].test:",
          "  algebra-laws.js[3.3.4]",
          "Module dependencies of algebra-laws.jvm[2.12.20]:",
          "  kernel-laws.jvm[2.12.20]",
          "  algebra-core.jvm[2.12.20]",
          "Module dependencies of algebra-laws.jvm[2.12.20].test:",
          "  algebra-laws.jvm[2.12.20]",
          "Module dependencies of algebra-laws.jvm[2.13.16]:",
          "  kernel-laws.jvm[2.13.16]",
          "  algebra-core.jvm[2.13.16]",
          "Module dependencies of algebra-laws.jvm[2.13.16].test:",
          "  algebra-laws.jvm[2.13.16]",
          "Module dependencies of algebra-laws.jvm[3.3.4]:",
          "  kernel-laws.jvm[3.3.4]",
          "  algebra-core.jvm[3.3.4]",
          "Module dependencies of algebra-laws.jvm[3.3.4].test:",
          "  algebra-laws.jvm[3.3.4]",
          "Module dependencies of algebra-laws.native[2.12.20]:",
          "  kernel-laws.native[2.12.20]",
          "  algebra-core.native[2.12.20]",
          "Module dependencies of algebra-laws.native[2.12.20].test:",
          "  algebra-laws.native[2.12.20]",
          "Module dependencies of algebra-laws.native[2.13.16]:",
          "  kernel-laws.native[2.13.16]",
          "  algebra-core.native[2.13.16]",
          "Module dependencies of algebra-laws.native[2.13.16].test:",
          "  algebra-laws.native[2.13.16]",
          "Module dependencies of algebra-laws.native[3.3.4]:",
          "  kernel-laws.native[3.3.4]",
          "  algebra-core.native[3.3.4]",
          "Module dependencies of algebra-laws.native[3.3.4].test:",
          "  algebra-laws.native[3.3.4]",
          "Module dependencies of alleycats-core.js[2.12.20]:",
          "  core.js[2.12.20]",
          "Module dependencies of alleycats-core.js[2.13.16]:",
          "  core.js[2.13.16]",
          "Module dependencies of alleycats-core.js[3.3.4]:",
          "  core.js[3.3.4]",
          "Module dependencies of alleycats-core.jvm[2.12.20]:",
          "  core.jvm[2.12.20]",
          "Module dependencies of alleycats-core.jvm[2.13.16]:",
          "  core.jvm[2.13.16]",
          "Module dependencies of alleycats-core.jvm[3.3.4]:",
          "  core.jvm[3.3.4]",
          "Module dependencies of alleycats-core.native[2.12.20]:",
          "  core.native[2.12.20]",
          "Module dependencies of alleycats-core.native[2.13.16]:",
          "  core.native[2.13.16]",
          "Module dependencies of alleycats-core.native[3.3.4]:",
          "  core.native[3.3.4]",
          "Module dependencies of alleycats-laws.js[2.12.20]:",
          "  alleycats-core.js[2.12.20]",
          "  laws.js[2.12.20]",
          "Module dependencies of alleycats-laws.js[2.12.20].test:",
          "  alleycats-laws.js[2.12.20]",
          "Module dependencies of alleycats-laws.js[2.13.16]:",
          "  alleycats-core.js[2.13.16]",
          "  laws.js[2.13.16]",
          "Module dependencies of alleycats-laws.js[2.13.16].test:",
          "  alleycats-laws.js[2.13.16]",
          "Module dependencies of alleycats-laws.js[3.3.4]:",
          "  alleycats-core.js[3.3.4]",
          "  laws.js[3.3.4]",
          "Module dependencies of alleycats-laws.js[3.3.4].test:",
          "  alleycats-laws.js[3.3.4]",
          "Module dependencies of alleycats-laws.jvm[2.12.20]:",
          "  alleycats-core.jvm[2.12.20]",
          "  laws.jvm[2.12.20]",
          "Module dependencies of alleycats-laws.jvm[2.12.20].test:",
          "  alleycats-laws.jvm[2.12.20]",
          "Module dependencies of alleycats-laws.jvm[2.13.16]:",
          "  alleycats-core.jvm[2.13.16]",
          "  laws.jvm[2.13.16]",
          "Module dependencies of alleycats-laws.jvm[2.13.16].test:",
          "  alleycats-laws.jvm[2.13.16]",
          "Module dependencies of alleycats-laws.jvm[3.3.4]:",
          "  alleycats-core.jvm[3.3.4]",
          "  laws.jvm[3.3.4]",
          "Module dependencies of alleycats-laws.jvm[3.3.4].test:",
          "  alleycats-laws.jvm[3.3.4]",
          "Module dependencies of alleycats-laws.native[2.12.20]:",
          "  alleycats-core.native[2.12.20]",
          "  laws.native[2.12.20]",
          "Module dependencies of alleycats-laws.native[2.12.20].test:",
          "  alleycats-laws.native[2.12.20]",
          "Module dependencies of alleycats-laws.native[2.13.16]:",
          "  alleycats-core.native[2.13.16]",
          "  laws.native[2.13.16]",
          "Module dependencies of alleycats-laws.native[2.13.16].test:",
          "  alleycats-laws.native[2.13.16]",
          "Module dependencies of alleycats-laws.native[3.3.4]:",
          "  alleycats-core.native[3.3.4]",
          "  laws.native[3.3.4]",
          "Module dependencies of alleycats-laws.native[3.3.4].test:",
          "  alleycats-laws.native[3.3.4]",
          "Module dependencies of bench[2.12.20]:",
          "  core.jvm[2.12.20]",
          "  free.jvm[2.12.20]",
          "  laws.jvm[2.12.20]",
          "Module dependencies of bench[2.13.16]:",
          "  core.jvm[2.13.16]",
          "  free.jvm[2.13.16]",
          "  laws.jvm[2.13.16]",
          "Module dependencies of bench[3.3.4]:",
          "  core.jvm[3.3.4]",
          "  free.jvm[3.3.4]",
          "  laws.jvm[3.3.4]",
          "Module dependencies of binCompatTest[2.12.20]:",
          "Module dependencies of binCompatTest[2.12.20].test:",
          "  binCompatTest[2.12.20]",
          "  core.jvm[2.12.20]",
          "Module dependencies of binCompatTest[2.13.16]:",
          "Module dependencies of binCompatTest[2.13.16].test:",
          "  binCompatTest[2.13.16]",
          "  core.jvm[2.13.16]",
          "Module dependencies of binCompatTest[3.3.4]:",
          "Module dependencies of binCompatTest[3.3.4].test:",
          "  binCompatTest[3.3.4]",
          "  core.jvm[3.3.4]",
          "Module dependencies of core.js[2.12.20]:",
          "  kernel.js[2.12.20]",
          "Module dependencies of core.js[2.12.20].test:",
          "  core.js[2.12.20]",
          "Module dependencies of core.js[2.13.16]:",
          "  kernel.js[2.13.16]",
          "Module dependencies of core.js[2.13.16].test:",
          "  core.js[2.13.16]",
          "Module dependencies of core.js[3.3.4]:",
          "  kernel.js[3.3.4]",
          "Module dependencies of core.js[3.3.4].test:",
          "  core.js[3.3.4]",
          "Module dependencies of core.jvm[2.12.20]:",
          "  kernel.jvm[2.12.20]",
          "Module dependencies of core.jvm[2.12.20].test:",
          "  core.jvm[2.12.20]",
          "Module dependencies of core.jvm[2.13.16]:",
          "  kernel.jvm[2.13.16]",
          "Module dependencies of core.jvm[2.13.16].test:",
          "  core.jvm[2.13.16]",
          "Module dependencies of core.jvm[3.3.4]:",
          "  kernel.jvm[3.3.4]",
          "Module dependencies of core.jvm[3.3.4].test:",
          "  core.jvm[3.3.4]",
          "Module dependencies of core.native[2.12.20]:",
          "  kernel.native[2.12.20]",
          "Module dependencies of core.native[2.12.20].test:",
          "  core.native[2.12.20]",
          "Module dependencies of core.native[2.13.16]:",
          "  kernel.native[2.13.16]",
          "Module dependencies of core.native[2.13.16].test:",
          "  core.native[2.13.16]",
          "Module dependencies of core.native[3.3.4]:",
          "  kernel.native[3.3.4]",
          "Module dependencies of core.native[3.3.4].test:",
          "  core.native[3.3.4]",
          "Module dependencies of free.js[2.12.20]:",
          "  core.js[2.12.20]",
          "Module dependencies of free.js[2.13.16]:",
          "  core.js[2.13.16]",
          "Module dependencies of free.js[3.3.4]:",
          "  core.js[3.3.4]",
          "Module dependencies of free.jvm[2.12.20]:",
          "  core.jvm[2.12.20]",
          "Module dependencies of free.jvm[2.13.16]:",
          "  core.jvm[2.13.16]",
          "Module dependencies of free.jvm[3.3.4]:",
          "  core.jvm[3.3.4]",
          "Module dependencies of free.native[2.12.20]:",
          "  core.native[2.12.20]",
          "Module dependencies of free.native[2.13.16]:",
          "  core.native[2.13.16]",
          "Module dependencies of free.native[3.3.4]:",
          "  core.native[3.3.4]",
          "Module dependencies of kernel.js[2.12.20]:",
          "Module dependencies of kernel.js[2.12.20].test:",
          "  kernel.js[2.12.20]",
          "Module dependencies of kernel.js[2.13.16]:",
          "Module dependencies of kernel.js[2.13.16].test:",
          "  kernel.js[2.13.16]",
          "Module dependencies of kernel.js[3.3.4]:",
          "Module dependencies of kernel.js[3.3.4].test:",
          "  kernel.js[3.3.4]",
          "Module dependencies of kernel.jvm[2.12.20]:",
          "Module dependencies of kernel.jvm[2.12.20].test:",
          "  kernel.jvm[2.12.20]",
          "Module dependencies of kernel.jvm[2.13.16]:",
          "Module dependencies of kernel.jvm[2.13.16].test:",
          "  kernel.jvm[2.13.16]",
          "Module dependencies of kernel.jvm[3.3.4]:",
          "Module dependencies of kernel.jvm[3.3.4].test:",
          "  kernel.jvm[3.3.4]",
          "Module dependencies of kernel.native[2.12.20]:",
          "Module dependencies of kernel.native[2.12.20].test:",
          "  kernel.native[2.12.20]",
          "Module dependencies of kernel.native[2.13.16]:",
          "Module dependencies of kernel.native[2.13.16].test:",
          "  kernel.native[2.13.16]",
          "Module dependencies of kernel.native[3.3.4]:",
          "Module dependencies of kernel.native[3.3.4].test:",
          "  kernel.native[3.3.4]",
          "Module dependencies of kernel-laws.js[2.12.20]:",
          "  kernel.js[2.12.20]",
          "Module dependencies of kernel-laws.js[2.12.20].test:",
          "  kernel-laws.js[2.12.20]",
          "Module dependencies of kernel-laws.js[2.13.16]:",
          "  kernel.js[2.13.16]",
          "Module dependencies of kernel-laws.js[2.13.16].test:",
          "  kernel-laws.js[2.13.16]",
          "Module dependencies of kernel-laws.js[3.3.4]:",
          "  kernel.js[3.3.4]",
          "Module dependencies of kernel-laws.js[3.3.4].test:",
          "  kernel-laws.js[3.3.4]",
          "Module dependencies of kernel-laws.jvm[2.12.20]:",
          "  kernel.jvm[2.12.20]",
          "Module dependencies of kernel-laws.jvm[2.12.20].test:",
          "  kernel-laws.jvm[2.12.20]",
          "Module dependencies of kernel-laws.jvm[2.13.16]:",
          "  kernel.jvm[2.13.16]",
          "Module dependencies of kernel-laws.jvm[2.13.16].test:",
          "  kernel-laws.jvm[2.13.16]",
          "Module dependencies of kernel-laws.jvm[3.3.4]:",
          "  kernel.jvm[3.3.4]",
          "Module dependencies of kernel-laws.jvm[3.3.4].test:",
          "  kernel-laws.jvm[3.3.4]",
          "Module dependencies of kernel-laws.native[2.12.20]:",
          "  kernel.native[2.12.20]",
          "Module dependencies of kernel-laws.native[2.12.20].test:",
          "  kernel-laws.native[2.12.20]",
          "Module dependencies of kernel-laws.native[2.13.16]:",
          "  kernel.native[2.13.16]",
          "Module dependencies of kernel-laws.native[2.13.16].test:",
          "  kernel-laws.native[2.13.16]",
          "Module dependencies of kernel-laws.native[3.3.4]:",
          "  kernel.native[3.3.4]",
          "Module dependencies of kernel-laws.native[3.3.4].test:",
          "  kernel-laws.native[3.3.4]",
          "Module dependencies of laws.js[2.12.20]:",
          "  kernel.js[2.12.20]",
          "  core.js[2.12.20]",
          "  kernel-laws.js[2.12.20]",
          "Module dependencies of laws.js[2.12.20].test:",
          "  laws.js[2.12.20]",
          "Module dependencies of laws.js[2.13.16]:",
          "  kernel.js[2.13.16]",
          "  core.js[2.13.16]",
          "  kernel-laws.js[2.13.16]",
          "Module dependencies of laws.js[2.13.16].test:",
          "  laws.js[2.13.16]",
          "Module dependencies of laws.js[3.3.4]:",
          "  kernel.js[3.3.4]",
          "  core.js[3.3.4]",
          "  kernel-laws.js[3.3.4]",
          "Module dependencies of laws.js[3.3.4].test:",
          "  laws.js[3.3.4]",
          "Module dependencies of laws.jvm[2.12.20]:",
          "  kernel.jvm[2.12.20]",
          "  core.jvm[2.12.20]",
          "  kernel-laws.jvm[2.12.20]",
          "Module dependencies of laws.jvm[2.12.20].test:",
          "  laws.jvm[2.12.20]",
          "Module dependencies of laws.jvm[2.13.16]:",
          "  kernel.jvm[2.13.16]",
          "  core.jvm[2.13.16]",
          "  kernel-laws.jvm[2.13.16]",
          "Module dependencies of laws.jvm[2.13.16].test:",
          "  laws.jvm[2.13.16]",
          "Module dependencies of laws.jvm[3.3.4]:",
          "  kernel.jvm[3.3.4]",
          "  core.jvm[3.3.4]",
          "  kernel-laws.jvm[3.3.4]",
          "Module dependencies of laws.jvm[3.3.4].test:",
          "  laws.jvm[3.3.4]",
          "Module dependencies of laws.native[2.12.20]:",
          "  kernel.native[2.12.20]",
          "  core.native[2.12.20]",
          "  kernel-laws.native[2.12.20]",
          "Module dependencies of laws.native[2.12.20].test:",
          "  laws.native[2.12.20]",
          "Module dependencies of laws.native[2.13.16]:",
          "  kernel.native[2.13.16]",
          "  core.native[2.13.16]",
          "  kernel-laws.native[2.13.16]",
          "Module dependencies of laws.native[2.13.16].test:",
          "  laws.native[2.13.16]",
          "Module dependencies of laws.native[3.3.4]:",
          "  kernel.native[3.3.4]",
          "  core.native[3.3.4]",
          "  kernel-laws.native[3.3.4]",
          "Module dependencies of laws.native[3.3.4].test:",
          "  laws.native[3.3.4]",
          "Module dependencies of site[2.12.20]:",
          "  core.jvm[2.12.20]",
          "  free.jvm[2.12.20]",
          "  laws.jvm[2.12.20]",
          "Module dependencies of site[2.13.16]:",
          "  core.jvm[2.13.16]",
          "  free.jvm[2.13.16]",
          "  laws.jvm[2.13.16]",
          "Module dependencies of site[3.3.4]:",
          "  core.jvm[3.3.4]",
          "  free.jvm[3.3.4]",
          "  laws.jvm[3.3.4]",
          "Module dependencies of testkit.js[2.12.20]:",
          "  core.js[2.12.20]",
          "  laws.js[2.12.20]",
          "Module dependencies of testkit.js[2.13.16]:",
          "  core.js[2.13.16]",
          "  laws.js[2.13.16]",
          "Module dependencies of testkit.js[3.3.4]:",
          "  core.js[3.3.4]",
          "  laws.js[3.3.4]",
          "Module dependencies of testkit.jvm[2.12.20]:",
          "  core.jvm[2.12.20]",
          "  laws.jvm[2.12.20]",
          "Module dependencies of testkit.jvm[2.13.16]:",
          "  core.jvm[2.13.16]",
          "  laws.jvm[2.13.16]",
          "Module dependencies of testkit.jvm[3.3.4]:",
          "  core.jvm[3.3.4]",
          "  laws.jvm[3.3.4]",
          "Module dependencies of testkit.native[2.12.20]:",
          "  core.native[2.12.20]",
          "  laws.native[2.12.20]",
          "Module dependencies of testkit.native[2.13.16]:",
          "  core.native[2.13.16]",
          "  laws.native[2.13.16]",
          "Module dependencies of testkit.native[3.3.4]:",
          "  core.native[3.3.4]",
          "  laws.native[3.3.4]",
          "Module dependencies of tests.js[2.12.20]:",
          "Module dependencies of tests.js[2.12.20].test:",
          "  tests.js[2.12.20]",
          "  testkit.js[2.12.20]",
          "Module dependencies of tests.js[2.13.16]:",
          "Module dependencies of tests.js[2.13.16].test:",
          "  tests.js[2.13.16]",
          "  testkit.js[2.13.16]",
          "Module dependencies of tests.js[3.3.4]:",
          "Module dependencies of tests.js[3.3.4].test:",
          "  tests.js[3.3.4]",
          "  testkit.js[3.3.4]",
          "Module dependencies of tests.jvm[2.12.20]:",
          "Module dependencies of tests.jvm[2.12.20].test:",
          "  tests.jvm[2.12.20]",
          "  testkit.jvm[2.12.20]",
          "Module dependencies of tests.jvm[2.13.16]:",
          "Module dependencies of tests.jvm[2.13.16].test:",
          "  tests.jvm[2.13.16]",
          "  testkit.jvm[2.13.16]",
          "Module dependencies of tests.jvm[3.3.4]:",
          "Module dependencies of tests.jvm[3.3.4].test:",
          "  tests.jvm[3.3.4]",
          "  testkit.jvm[3.3.4]",
          "Module dependencies of tests.native[2.12.20]:",
          "Module dependencies of tests.native[2.12.20].test:",
          "  tests.native[2.12.20]",
          "  testkit.native[2.12.20]",
          "Module dependencies of tests.native[2.13.16]:",
          "Module dependencies of tests.native[2.13.16].test:",
          "  tests.native[2.13.16]",
          "  testkit.native[2.13.16]",
          "Module dependencies of tests.native[3.3.4]:",
          "Module dependencies of tests.native[3.3.4].test:",
          "  tests.native[3.3.4]",
          "  testkit.native[3.3.4]",
          "Module dependencies of unidocs[2.12.20]:",
          "Module dependencies of unidocs[2.13.16]:",
          "Module dependencies of unidocs[3.3.4]:"
        )
      )

      // subsequent checks are limited to a module and its test module to reduce the literal sizes
      val selectorPrefix = "kernel.__."

      val mvnDeps = showNamedMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "kernel.native[2.13.16].mvnDeps" -> List(),
          "kernel.native[2.12.20].test.mvnDeps" -> List(
            "org.scala-native::test-interface::0.5.6",
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.js[3.3.4].test.mvnDeps" -> List(
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.jvm[3.3.4].test.mvnDeps" -> List(
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.native[3.3.4].test.mvnDeps" -> List(
            "org.scala-native::test-interface::0.5.6",
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.jvm[3.3.4].mvnDeps" -> List(),
          "kernel.native[2.13.16].test.mvnDeps" -> List(
            "org.scala-native::test-interface::0.5.6",
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.js[2.12.20].mvnDeps" -> List(),
          "kernel.jvm[2.13.16].test.mvnDeps" -> List(
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.jvm[2.12.20].test.mvnDeps" -> List(
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.js[2.13.16].test.mvnDeps" -> List(
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.native[2.12.20].mvnDeps" -> List(),
          "kernel.js[3.3.4].mvnDeps" -> List(),
          "kernel.jvm[2.12.20].mvnDeps" -> List(),
          "kernel.native[3.3.4].mvnDeps" -> List(),
          "kernel.js[2.12.20].test.mvnDeps" -> List(
            "org.scalameta::munit::1.0.4",
            "org.typelevel::discipline-munit::2.0.0"
          ),
          "kernel.js[2.13.16].mvnDeps" -> List(),
          "kernel.jvm[2.13.16].mvnDeps" -> List()
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        compileMvnDeps,
        Map(
          "kernel.jvm[3.3.4].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.native[3.3.4].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.js[2.12.20].test.compileMvnDeps" -> List(),
          "kernel.js[2.12.20].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.native[2.13.16].test.compileMvnDeps" -> List(),
          "kernel.js[2.13.16].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.jvm[2.12.20].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.native[3.3.4].test.compileMvnDeps" -> List(),
          "kernel.jvm[2.12.20].test.compileMvnDeps" -> List(),
          "kernel.jvm[2.13.16].test.compileMvnDeps" -> List(),
          "kernel.native[2.12.20].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.native[2.12.20].test.compileMvnDeps" -> List(),
          "kernel.js[2.13.16].test.compileMvnDeps" -> List(),
          "kernel.native[2.13.16].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.js[3.3.4].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          ),
          "kernel.js[3.3.4].test.compileMvnDeps" -> List(),
          "kernel.jvm[3.3.4].test.compileMvnDeps" -> List(),
          "kernel.jvm[2.13.16].compileMvnDeps" -> List(
            "org.typelevel::scalac-compat-annotation:0.1.4"
          )
        )
      )
      val javacOptions = showNamedJavacOptions(tester, selectorPrefix)
      assertGoldenLiteral(
        javacOptions,
        Map(
          "kernel.jvm[3.3.4].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.jvm[2.13.16].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.jvm[2.12.20].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.js[2.13.16].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.jvm[2.12.20].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.js[3.3.4].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.native[3.3.4].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.js[2.12.20].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.native[2.12.20].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.native[2.12.20].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.js[3.3.4].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.js[2.12.20].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.native[2.13.16].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.native[2.13.16].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.jvm[3.3.4].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.native[3.3.4].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.js[2.13.16].test.javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          ),
          "kernel.jvm[2.13.16].javacOptions" -> List(
            "-encoding",
            "utf8",
            "-Xlint:all",
            "--release",
            "8"
          )
        )
      )

      val compileRes = eval("kernel.jvm[2.13.16].__.compile")
      assert(
        !compileRes.isSuccess,
        compileRes.err.contains("AllInstances.scala:55:10: not found: type TupleInstances")
      )
    }
  }
}
