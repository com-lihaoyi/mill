package mill.integration

import mill.integration.IntegrationTesterUtil.*
import mill.javalib.publish.*
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitImportGradleTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("FastCSV") - integrationTestGitRepo(
      "https://github.com/osiegmar/FastCSV.git",
      "v4.0.0"
    ) { tester =>
      import tester.*

      val initRes = eval(("init", "--gradle-jvm-id", "24"))
      assert(initRes.isSuccess)

      val showModuleDeps = eval("__.showModuleDeps").out.linesIterator.toSeq
      assertGoldenLiteral(
        showModuleDeps,
        List(
          "Module dependencies of example:",
          "  lib",
          "Module dependencies of lib:",
          "Module dependencies of lib.test:",
          "  lib"
        )
      )

      val repositories = showNamedRepositories(tester)
      assertGoldenLiteral(
        repositories,
        Map(
          "example.repositories" -> List(),
          "lib.repositories" -> List(),
          "lib.test.repositories" -> List()
        )
      )

      val jvmId = showNamedJvmId(tester)
      assertGoldenLiteral(jvmId, Map("example.jvmId" -> "zulu:24", "lib.jvmId" -> "zulu:17"))

      val mvnDeps = showNamedMvnDeps(tester)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "example.mvnDeps" -> List("ch.randelshofer:fastdoubleparser:2.0.1"),
          "lib.mvnDeps" -> List(),
          "lib.test.mvnDeps" -> List(
            "org.junit.jupiter:junit-jupiter:5.13.1",
            "org.assertj:assertj-core:3.27.3"
          )
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester)
      assertGoldenLiteral(
        compileMvnDeps,
        Map(
          "example.compileMvnDeps" -> List(),
          "lib.compileMvnDeps" -> List(),
          "lib.test.compileMvnDeps" -> List()
        )
      )
      val runMvnDeps = showNamedRunMvnDeps(tester)
      assertGoldenLiteral(
        runMvnDeps,
        Map(
          "example.runMvnDeps" -> List(),
          "lib.runMvnDeps" -> List(),
          "lib.test.runMvnDeps" -> List("org.junit.platform:junit-platform-launcher:")
        )
      )
      val bomMvnDeps = showNamedBomMvnDeps(tester)
      assertGoldenLiteral(
        bomMvnDeps,
        Map(
          "example.bomMvnDeps" -> List(),
          "lib.bomMvnDeps" -> List(),
          "lib.test.bomMvnDeps" -> List()
        )
      )
      val javacOptions = showNamedJavacOptions(tester)
      assertGoldenLiteral(
        javacOptions,
        Map(
          "example.javacOptions" -> List("-source", "24", "-target", "24"),
          "lib.javacOptions" -> List("--release", "17", "-Xlint:all", "-Werror"),
          "lib.test.javacOptions" -> List(
            "--release",
            "17",
            "-Xlint:all",
            "-Werror",
            "-source",
            "24",
            "-target",
            "24",
            "-parameters"
          )
        )
      )

      val pomParentProject = showNamedPomParentProject(tester)
      assertGoldenLiteral(
        pomParentProject,
        Map("lib.pomParentProject" -> None)
      )
      val pomSettings = showNamedPomSettings(tester)
      assertGoldenLiteral(
        pomSettings,
        Map(
          "lib.pomSettings" -> PomSettings(
            description =
              "Lightning-fast, dependency-free CSV library that conforms to RFC standards.",
            organization = "de.siegmar",
            url = "https://fastcsv.org",
            licenses = List(
              License(
                id = "",
                name = "MIT License",
                url = "https://opensource.org/licenses/MIT",
                isOsiApproved = false,
                isFsfLibre = false,
                distribution = ""
              )
            ),
            versionControl = VersionControl(
              browsableRepository = Some("https://github.com/osiegmar/FastCSV"),
              connection = Some("scm:git:https://github.com/osiegmar/FastCSV.git"),
              developerConnection = None,
              tag = None
            ),
            developers = List(
              Developer(
                id = "osiegmar",
                name = "Oliver Siegmar",
                url = "",
                organization = None,
                organizationUrl = None
              )
            ),
            packaging = "jar"
          )
        )
      )
      val publishVersion = showNamedPublishVersion(tester)
      assertGoldenLiteral(publishVersion, Map("lib.publishVersion" -> "4.0.0"))

      val errorProneVersion = showNamedErrorProneVersion(tester)
      assertGoldenLiteral(
        errorProneVersion,
        Map("lib.errorProneVersion" -> "2.38.0", "lib.test.errorProneVersion" -> "2.38.0")
      )
      val errorProneDeps = showNamedErrorProneDeps(tester)
      assertGoldenLiteral(
        errorProneDeps,
        Map(
          "lib.errorProneDeps" -> List(
            "com.google.errorprone:error_prone_core:2.38.0",
            "com.uber.nullaway:nullaway:0.12.7"
          ),
          "lib.test.errorProneDeps" -> List(
            "com.google.errorprone:error_prone_core:2.38.0",
            "com.uber.nullaway:nullaway:0.12.7"
          )
        )
      )
      val errorProneOptions = showNamedErrorProneOptions(tester)
      assertGoldenLiteral(
        errorProneOptions,
        Map(
          "lib.errorProneOptions" -> List(
            "-Xep:NullAway:ERROR",
            "-XepOpt:NullAway:AnnotatedPackages=de.siegmar.fastcsv"
          ),
          "lib.test.errorProneOptions" -> List(
            "-Xep:NullAway:ERROR",
            "-XepOpt:NullAway:AnnotatedPackages=de.siegmar.fastcsv"
          )
        )
      )

      val libCompileRes = tester.eval("lib.compile")
      assert(
        !libCompileRes.isSuccess,
        libCompileRes.err.contains("warnings found and -Werror specified")
      )
    }

    test("spring-framework") - integrationTestGitRepo(
      "https://github.com/spring-projects/spring-framework.git",
      "v6.2.11"
    ) { tester =>
      import tester.*

      val initRes = eval(("init", "--gradle-jvm-id", "17"))
      assert(initRes.isSuccess)

      val showModuleDeps = eval("__.showModuleDeps").out.linesIterator.toSeq
      assertGoldenLiteral(
        showModuleDeps,
        List(
          "Module dependencies of framework-api:",
          "Module dependencies of framework-bom:",
          "Module dependencies of framework-docs:",
          "  spring-aspects",
          "  spring-context",
          "  spring-context-support",
          "  spring-core-test",
          "  spring-jdbc",
          "  spring-jms",
          "  spring-test",
          "  spring-web",
          "  spring-webflux",
          "  spring-webmvc",
          "  spring-websocket",
          "Module dependencies of framework-platform:",
          "Module dependencies of integration-tests:",
          "Module dependencies of integration-tests.test:",
          "  integration-tests",
          "  spring-aop",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "  spring-core-test",
          "  spring-tx",
          "  spring-expression",
          "  spring-jdbc",
          "  spring-orm",
          "  spring-test",
          "  spring-web",
          "Module dependencies of :",
          "Module dependencies of spring-aop:",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-aop.test:",
          "  spring-aop",
          "  spring-core-test",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-aspects:",
          "Module dependencies of spring-aspects.test:",
          "  spring-aspects",
          "  spring-core",
          "  spring-test",
          "  spring-context",
          "  spring-context-support",
          "  spring-tx",
          "Module dependencies of spring-beans:",
          "  spring-core",
          "Module dependencies of spring-beans.test:",
          "  spring-beans",
          "  spring-core-test",
          "  spring-core",
          "Module dependencies of spring-context:",
          "  spring-aop",
          "  spring-beans",
          "  spring-core",
          "  spring-expression",
          "Module dependencies of spring-context.test:",
          "  spring-context",
          "  spring-core-test",
          "  spring-aop",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-context-indexer:",
          "Module dependencies of spring-context-indexer.test:",
          "  spring-context-indexer",
          "  spring-context",
          "Module dependencies of spring-context-support:",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "Module dependencies of spring-context-support.test:",
          "  spring-context-support",
          "  spring-context",
          "  spring-beans",
          "  spring-core",
          "  spring-tx",
          "Module dependencies of spring-core:",
          "  spring-jcl",
          "Module dependencies of spring-core.test:",
          "  spring-core",
          "Module dependencies of spring-core-test:",
          "  spring-core",
          "Module dependencies of spring-core-test.test:",
          "  spring-core-test",
          "Module dependencies of spring-expression:",
          "  spring-core",
          "Module dependencies of spring-expression.test:",
          "  spring-expression",
          "  spring-core",
          "Module dependencies of spring-instrument:",
          "Module dependencies of spring-jcl:",
          "Module dependencies of spring-jdbc:",
          "  spring-beans",
          "  spring-core",
          "  spring-tx",
          "Module dependencies of spring-jdbc.test:",
          "  spring-jdbc",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-jms:",
          "  spring-beans",
          "  spring-core",
          "  spring-messaging",
          "  spring-tx",
          "Module dependencies of spring-jms.test:",
          "  spring-jms",
          "  spring-beans",
          "  spring-tx",
          "Module dependencies of spring-messaging:",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-messaging.test:",
          "  spring-messaging",
          "  spring-core-test",
          "  spring-core",
          "  spring-context (runtime)",
          "Module dependencies of spring-orm:",
          "  spring-beans",
          "  spring-core",
          "  spring-jdbc",
          "  spring-tx",
          "Module dependencies of spring-orm.test:",
          "  spring-orm",
          "  spring-core-test",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "  spring-web",
          "Module dependencies of spring-oxm:",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-oxm.test:",
          "  spring-oxm",
          "  spring-context",
          "  spring-core",
          "Module dependencies of spring-r2dbc:",
          "  spring-beans",
          "  spring-core",
          "  spring-tx",
          "Module dependencies of spring-r2dbc.test:",
          "  spring-r2dbc",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "Module dependencies of spring-test:",
          "  spring-core",
          "Module dependencies of spring-test.test:",
          "  spring-test",
          "  spring-context-support",
          "  spring-core-test",
          "  spring-oxm",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "  spring-tx",
          "  spring-web",
          "Module dependencies of spring-tx:",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-tx.test:",
          "  spring-tx",
          "  spring-core-test",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "Module dependencies of spring-web:",
          "  spring-beans",
          "  spring-core",
          "Module dependencies of spring-web.test:",
          "  spring-web",
          "  spring-core-test",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "Module dependencies of spring-webflux:",
          "  spring-beans",
          "  spring-core",
          "  spring-web",
          "Module dependencies of spring-webflux.test:",
          "  spring-webflux",
          "  spring-beans",
          "  spring-core",
          "  spring-web",
          "Module dependencies of spring-webmvc:",
          "  spring-aop",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "  spring-expression",
          "  spring-web",
          "Module dependencies of spring-webmvc.test:",
          "  spring-webmvc",
          "  spring-beans",
          "  spring-context",
          "  spring-core",
          "  spring-web",
          "Module dependencies of spring-websocket:",
          "  spring-context",
          "  spring-core",
          "  spring-web",
          "Module dependencies of spring-websocket.test:",
          "  spring-websocket",
          "  spring-core",
          "  spring-web"
        )
      )

      // subsequent checks are limited to a module and its test module to reduce the literal sizes
      val selectorPrefix = "spring-core.__."

      val repositories = showNamedRepositories(tester, selectorPrefix)
      assertGoldenLiteral(
        repositories,
        Map(
          "spring-core.repositories" -> List("https://repo.spring.io/milestone"),
          "spring-core.test.repositories" -> List()
        )
      )

      val jvmId = showNamedJvmId(tester, selectorPrefix)
      assertGoldenLiteral(
        jvmId,
        Map("spring-core.jvmId" -> "zulu:17")
      )

      val mvnDeps = showNamedMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "spring-core.mvnDeps" -> List(),
          "spring-core.test.mvnDeps" -> List(
            "org.junit.jupiter:junit-jupiter:",
            "org.junit.platform:junit-platform-suite:",
            "org.mockito:mockito-core:",
            "org.mockito:mockito-junit-jupiter:",
            "io.mockk:mockk:",
            "org.assertj:assertj-core:",
            "com.fasterxml.jackson.core:jackson-databind:",
            "com.fasterxml.woodstox:woodstox-core:",
            "com.google.code.findbugs:jsr305:",
            "com.squareup.okhttp3:mockwebserver:",
            "io.projectreactor:reactor-test:",
            "io.projectreactor.tools:blockhound:",
            "jakarta.annotation:jakarta.annotation-api:",
            "jakarta.xml.bind:jakarta.xml.bind-api:",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:",
            "org.jetbrains.kotlinx:kotlinx-coroutines-reactor:",
            "org.skyscreamer:jsonassert:",
            "org.xmlunit:xmlunit-assertj:",
            "org.xmlunit:xmlunit-matchers:"
          )
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        compileMvnDeps,
        Map(
          "spring-core.compileMvnDeps" -> List(
            "com.google.code.findbugs:jsr305:",
            "io.projectreactor.tools:blockhound:",
            "org.graalvm.sdk:graal-sdk:"
          ),
          "spring-core.test.compileMvnDeps" -> List("com.google.code.findbugs:jsr305:")
        )
      )
      val runMvnDeps = showNamedCompileMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        runMvnDeps,
        Map(
          "spring-core.compileMvnDeps" -> List(
            "com.google.code.findbugs:jsr305:",
            "io.projectreactor.tools:blockhound:",
            "org.graalvm.sdk:graal-sdk:"
          ),
          "spring-core.test.compileMvnDeps" -> List("com.google.code.findbugs:jsr305:")
        )
      )
      val javacOptions = showNamedJavacOptions(tester, selectorPrefix)
      assertGoldenLiteral(
        javacOptions,
        Map(
          "spring-core.javacOptions" -> List(
            "-source",
            "17",
            "-target",
            "17",
            "-encoding",
            "UTF-8",
            "-Xlint:serial",
            "-Xlint:cast",
            "-Xlint:classfile",
            "-Xlint:dep-ann",
            "-Xlint:divzero",
            "-Xlint:empty",
            "-Xlint:finally",
            "-Xlint:overrides",
            "-Xlint:path",
            "-Xlint:processing",
            "-Xlint:static",
            "-Xlint:try",
            "-Xlint:-options",
            "-parameters",
            "-Xlint:varargs",
            "-Xlint:fallthrough",
            "-Xlint:rawtypes",
            "-Xlint:deprecation",
            "-Xlint:unchecked",
            "-Werror"
          ),
          "spring-core.test.javacOptions" -> List(
            "-source",
            "17",
            "-target",
            "17",
            "-encoding",
            "UTF-8",
            "-Xlint:serial",
            "-Xlint:cast",
            "-Xlint:classfile",
            "-Xlint:dep-ann",
            "-Xlint:divzero",
            "-Xlint:empty",
            "-Xlint:finally",
            "-Xlint:overrides",
            "-Xlint:path",
            "-Xlint:processing",
            "-Xlint:static",
            "-Xlint:try",
            "-Xlint:-options",
            "-parameters",
            "-Xlint:varargs",
            "-Xlint:fallthrough",
            "-Xlint:rawtypes",
            "-Xlint:deprecation",
            "-Xlint:unchecked",
            "-Werror",
            "-Xlint:-varargs",
            "-Xlint:-fallthrough",
            "-Xlint:-rawtypes",
            "-Xlint:-deprecation",
            "-Xlint:-unchecked"
          )
        )
      )

      val pomParentProject = showNamedPomParentProject(tester, selectorPrefix)
      assertGoldenLiteral(pomParentProject, Map("spring-core.pomParentProject" -> None))
      val pomSettings = showNamedPomSettings(tester, selectorPrefix)
      assertGoldenLiteral(
        pomSettings,
        Map(
          "spring-core.pomSettings" -> PomSettings(
            description = "Spring Core",
            organization = "org.springframework",
            url = "https://github.com/spring-projects/spring-framework",
            licenses = List(
              License(
                id = "",
                name = "Apache License, Version 2.0",
                url = "https://www.apache.org/licenses/LICENSE-2.0",
                isOsiApproved = false,
                isFsfLibre = false,
                distribution = "repo"
              )
            ),
            versionControl = VersionControl(
              browsableRepository = Some("https://github.com/spring-projects/spring-framework"),
              connection = Some("scm:git:git://github.com/spring-projects/spring-framework"),
              developerConnection =
                Some("scm:git:git://github.com/spring-projects/spring-framework"),
              tag = None
            ),
            developers = List(
              Developer(
                id = "jhoeller",
                name = "Juergen Hoeller",
                url = "",
                organization = None,
                organizationUrl = None
              )
            ),
            packaging = "jar"
          )
        )
      )
      val publishVersion = showNamedPublishVersion(tester, selectorPrefix)
      assertGoldenLiteral(publishVersion, Map("spring-core.publishVersion" -> "6.2.11"))

      val errorProneVersion = showNamedErrorProneVersion(tester, selectorPrefix)
      assertGoldenLiteral(
        errorProneVersion,
        Map(
          "spring-core.errorProneVersion" -> "2.9.0",
          "spring-core.test.errorProneVersion" -> "2.9.0"
        )
      )
      val errorProneDeps = showNamedErrorProneDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        errorProneDeps,
        Map(
          "spring-core.errorProneDeps" -> List(
            "com.google.errorprone:error_prone_core:2.9.0",
            "com.uber.nullaway:nullaway:0.10.26"
          ),
          "spring-core.test.errorProneDeps" -> List(
            "com.google.errorprone:error_prone_core:2.9.0",
            "com.uber.nullaway:nullaway:0.10.26"
          )
        )
      )
      val errorProneOptions = showNamedErrorProneOptions(tester, selectorPrefix)
      assertGoldenLiteral(
        errorProneOptions,
        Map(
          "spring-core.errorProneOptions" -> List(
            "-XepDisableAllChecks",
            "-Xep:NullAway:ERROR",
            "-XepOpt:NullAway:CustomContractAnnotations=org.springframework.lang.Contract",
            "-XepOpt:NullAway:AnnotatedPackages=org.springframework",
            "-XepOpt:NullAway:UnannotatedSubPackages=org.springframework.instrument,org.springframework.context.index,org.springframework.asm,org.springframework.cglib,org.springframework.objenesis,org.springframework.javapoet,org.springframework.aot.nativex.substitution,org.springframework.aot.nativex.feature"
          ),
          "spring-core.test.errorProneOptions" -> List(
            "-XepDisableAllChecks",
            "-Xep:NullAway:ERROR",
            "-XepOpt:NullAway:CustomContractAnnotations=org.springframework.lang.Contract",
            "-XepOpt:NullAway:AnnotatedPackages=org.springframework",
            "-XepOpt:NullAway:UnannotatedSubPackages=org.springframework.instrument,org.springframework.context.index,org.springframework.asm,org.springframework.cglib,org.springframework.objenesis,org.springframework.javapoet,org.springframework.aot.nativex.substitution,org.springframework.aot.nativex.feature"
          )
        )
      )

      val springCoreCompileRes = eval("spring-core.compile")
      assert(
        !springCoreCompileRes.isSuccess,
        springCoreCompileRes.err.contains("spring-core.resolvedMvnDeps java.lang.RuntimeException")
      )
    }
  }
}
