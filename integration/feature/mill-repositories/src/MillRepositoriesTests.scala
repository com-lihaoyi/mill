package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

/**
 * Tests the `mill-repositories` configuration which allows configuring global repositories
 * for resolving Mill's own JARs (daemon, JVM index).
 */
object MillRepositoriesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      import tester._

      // Set up a custom local repo by copying the Mill artifacts
      val millProjectRoot = os.Path(sys.env("MILL_PROJECT_ROOT"))
      val sourceLocalRepo = millProjectRoot / "out" / "dist" / "raw" / "localRepo.dest"
      val customLocalRepo = workspacePath / "custom-local-repo"
      os.copy(sourceLocalRepo, customLocalRepo)

      val initialize = eval(("version"))
      assert(initialize.isSuccess) // initialize daemon

      assertGoldenLiteral(
        upickle.read[(String, Seq[String])](
          os.read(workspacePath / "out/mill-daemon/cache/mill-daemon-classpath")
            .linesIterator
            .toSeq
            .head
        ),
        List(
          "[",
          "  \"SNAPSHOT::file:///Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/\",",
          "  [",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-daemon_3/SNAPSHOT/mill-runner-daemon_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.4/sourcecode_3-0.4.4.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_3/0.11.7/os-lib_3-0.11.7.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib-watch_3/0.11.7/os-lib-watch_3-0.11.7.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/mainargs_3/0.7.8/mainargs_3-0.7.8.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_3/4.4.2/upickle_3-4.4.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.6/pprint_3-0.9.6.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/fansi_3/0.5.1/fansi_3-0.5.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.1/scala3-compiler_3-3.8.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-sbt/compiler-interface/2.0.0-M13/compiler-interface-2.0.0-M13.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.8.1/scala3-library_3-3.8.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/mill-moduledefs_3/0.13.1/mill-moduledefs_3-0.13.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/unroll-annotation_3/0.2.0/unroll-annotation_3-0.2.0.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-eclipse_3/SNAPSHOT/mill-runner-eclipse_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-idea_3/SNAPSHOT/mill-runner-idea_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-bsp_3/SNAPSHOT/mill-runner-bsp_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-bsp-worker_3/SNAPSHOT/mill-runner-bsp-worker_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-eval_3/SNAPSHOT/mill-core-eval_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-server_3/SNAPSHOT/mill-runner-server_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-launcher_3/SNAPSHOT/mill-runner-launcher_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-meta_3/SNAPSHOT/mill-runner-meta_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-script_3/SNAPSHOT/mill-libs-script_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-init_3/SNAPSHOT/mill-libs-init_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_3/1.1.1/geny_3-1.1.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.7/os-zip-0.11.7.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_3/2.12.0/scala-collection-compat_3-2.12.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_3/4.4.2/ujson_3-4.4.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_3/4.4.2/upack_3-4.4.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_3/4.4.2/upickle-implicits_3-4.4.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-interfaces/3.8.1/scala3-interfaces-3.8.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/tasty-core_3/3.8.1/tasty-core_3-3.8.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-sbt/util-interface/2.0.0-RC8/util-interface-2.0.0-RC8.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/3.8.1/scala-library-3.8.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0/scala-xml_3-2.4.0.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-util_3/SNAPSHOT/mill-libs-util_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-internal_3/SNAPSHOT/mill-core-internal_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/6.10.1.202505221210-r/org.eclipse.jgit-6.10.1.202505221210-r.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/ch/epfl/scala/bsp4j/2.2.0-M2/bsp4j-2.2.0-M2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-sbt/test-interface/1.0/test-interface-1.0.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-api_3/SNAPSHOT/mill-core-api_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-exec_3/SNAPSHOT/mill-core-exec_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-resolve_3/SNAPSHOT/mill-core-resolve_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-api-daemon_3/SNAPSHOT/mill-core-api-daemon_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-daemon-server_3/SNAPSHOT/mill-libs-daemon-server_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/native-terminal/native-terminal-no-ffm/0.0.9.1/native-terminal-no-ffm-0.0.9.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-jvm_2.13/2.1.25-M23/coursier-jvm_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.5.24/logback-classic-1.5.24.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-rpc_3/SNAPSHOT/mill-libs-rpc_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-codesig_3/SNAPSHOT/mill-runner-codesig_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-scalalib_3/SNAPSHOT/mill-libs-scalalib_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-kotlinlib_3/SNAPSHOT/mill-libs-kotlinlib_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-groovylib_3/SNAPSHOT/mill-libs-groovylib_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_3/4.4.2/upickle-core_3-4.4.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.25-M23/coursier_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-archive-cache_2.13/2.1.25-M23/coursier-archive-cache_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.25-M23/coursier-core_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.25-M23/coursier-cache_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-paths/2.1.25-M23/coursier-paths-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.25-M23/coursier-util_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.1/versions_2.13-0.5.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/jline/jline/3.30.6/jline-3.30.6.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/fastparse_3/3.1.1/fastparse_3-3.1.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/requests_3/0.9.2/requests_3-0.9.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/jgrapht/jgrapht-core/1.4.0/jgrapht-core-1.4.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/guru/nidi/graphviz-java-min-deps/0.18.1/graphviz-java-min-deps-0.18.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/snakeyaml/snakeyaml-engine/3.0.1/snakeyaml-engine-3.0.1.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-internal-cli_3/SNAPSHOT/mill-core-internal-cli_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/commons-codec/commons-codec/1.19.0/commons-codec-1.19.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/eclipse/lsp4j/org.eclipse.lsp4j.generator/0.20.1/org.eclipse.lsp4j.generator-0.20.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/eclipse/lsp4j/org.eclipse.lsp4j.jsonrpc/0.20.1/org.eclipse.lsp4j.jsonrpc-0.20.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.41.0/error_prone_annotations-2.41.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits-named-tuples_3/4.4.2/upickle-implicits-named-tuples_3-4.4.2.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-daemon-client_3/SNAPSHOT/mill-libs-daemon-client_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-api-java11_3/SNAPSHOT/mill-core-api-java11_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/is-terminal/0.1.2/is-terminal-0.1.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/jline/jline-native/3.29.0/jline-native-3.29.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5/jsoniter-scala-core_2.13-2.13.5.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-env_2.13/2.1.25-M23/coursier-env_2.13-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-exec/2.1.25-M23/coursier-exec-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/ch/qos/logback/logback-core/1.5.24/logback-core-1.5.24.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lumidion/sonatype-central-client-requests_3/0.6.0/sonatype-central-client-requests_3-0.6.0.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib_3/SNAPSHOT/mill-libs-javalib_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib-testrunner_3/SNAPSHOT/mill-libs-javalib-testrunner_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-kotlinlib-api_3/SNAPSHOT/mill-libs-kotlinlib-api_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/SNAPSHOT/mill-libs-kotlinlib-ksp2-api_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-groovylib-api_3/SNAPSHOT/mill-libs-groovylib-api_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/jheaps/jheaps/0.11/jheaps-0.11.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/guru/nidi/graphviz-java/0.18.1/graphviz-java-0.18.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/eclipse/xtend/org.eclipse.xtend.lib/2.28.0/org.eclipse.xtend.lib-2.28.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/dependency_2.13/0.3.2/dependency_2.13-0.3.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-proxy-setup/2.1.25-M23/coursier-proxy-setup-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-archiver/4.10.1/plexus-archiver-4.10.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-container-default/2.1.1/plexus-container-default-2.1.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/tika/tika-core/3.2.3/tika-core-3.2.3.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-4/zstd-jni-1.5.7-4.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.13.0/scala-collection-compat_2.13-2.13.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/jniutils/windows-jni-utils/0.3.3/windows-jni-utils-0.3.3.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lumidion/sonatype-central-client-core_3/0.6.0/sonatype-central-client-core_3-0.6.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lumidion/sonatype-central-client-upickle_3/0.6.0/sonatype-central-client-upickle_3-0.6.0.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib-api_3/SNAPSHOT/mill-libs-javalib-api_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/SNAPSHOT/mill-libs-javalib-testrunner-entrypoint-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-util-java11_3/SNAPSHOT/mill-libs-util-java11_3-SNAPSHOT.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/slf4j/jcl-over-slf4j/1.7.30/jcl-over-slf4j-1.7.30.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/slf4j/jul-to-slf4j/1.7.30/jul-to-slf4j-1.7.30.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/eclipse/xtext/org.eclipse.xtext.xbase.lib/2.28.0/org.eclipse.xtext.xbase.lib-2.28.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/eclipse/xtend/org.eclipse.xtend.lib.macro/2.28.0/org.eclipse.xtend.lib.macro-2.28.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0/concurrent-reference-hash-map-1.1.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.4.0/scala-xml_2.13-2.4.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/virtuslab/scala-cli/config_3/1.9.1/config_3-1.9.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.6/windows-ansi-0.0.6.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/cache-util/2.1.25-M23/cache-util-2.1.25-M23.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/4.0.2/plexus-utils-4.0.2.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-io/3.5.1/plexus-io-3.5.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/airlift/aircompressor/0.27/aircompressor-0.27.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/tukaani/xz/1.10/xz-1.10.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-classworlds/2.6.0/plexus-classworlds-2.6.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/xbean/xbean-reflect/3.7/xbean-reflect-3.7.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/guava/guava/30.1-jre/guava-30.1-jre.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/virtuslab/scala-cli/specification-level_3/1.9.1/specification-level_3-1.9.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/fusesource/jansi/jansi/2.4.1/jansi-2.4.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/checkerframework/checker-qual/3.5.0/checker-qual-3.5.0.jar\",",
          "    \"/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/1.3/j2objc-annotations-1.3.jar\"",
          "  ]",
          "]"
        )
      )

      val fooRepositories = eval(("show", "foo.repositories"))
      assert(fooRepositories.isSuccess)
      assertGoldenLiteral(
        upickle.read[Seq[String]](fooRepositories.out),
        List(
          "[",
          "  \"ref:v0:c984eca8:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/foo/compile-resources\"",
          "]"
        )
      )

      val metaClasspath = eval(("--meta-level", "1", "show", "compileClasspath"))
      assert(metaClasspath.isSuccess)
      assertGoldenLiteral(
        upickle.read[Seq[String]](metaClasspath.out),
        List(
          "[",
          "  \"qref:v1:a32b9cc1:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.4/sourcecode_3-0.4.4.jar\",",
          "  \"qref:v1:5fe7d4c2:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs_3/SNAPSHOT/mill-libs_3-SNAPSHOT.jar\",",
          "  \"qref:v1:acecf477:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-runner-autooverride-api_3/SNAPSHOT/mill-runner-autooverride-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:f0eb6794:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.8.1/scala3-library_3-3.8.1.jar\",",
          "  \"qref:v1:c3606d19:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/mill-moduledefs_3/0.13.1/mill-moduledefs_3-0.13.1.jar\",",
          "  \"qref:v1:66928462:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/unroll-annotation_3/0.2.0/unroll-annotation_3-0.2.0.jar\",",
          "  \"qref:v1:861badcd:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-kotlinlib_3/SNAPSHOT/mill-libs-kotlinlib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:d1b966f6:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-groovylib_3/SNAPSHOT/mill-libs-groovylib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:17a6cb03:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-androidlib_3/SNAPSHOT/mill-libs-androidlib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:74803e04:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-scalajslib_3/SNAPSHOT/mill-libs-scalajslib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:a2184473:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-scalanativelib_3/SNAPSHOT/mill-libs-scalanativelib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:4d103c72:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javascriptlib_3/SNAPSHOT/mill-libs-javascriptlib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:da3e6e86:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-pythonlib_3/SNAPSHOT/mill-libs-pythonlib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:e1a607b7:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-util_3/SNAPSHOT/mill-libs-util_3-SNAPSHOT.jar\",",
          "  \"qref:v1:610785b3:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-script_3/SNAPSHOT/mill-libs-script_3-SNAPSHOT.jar\",",
          "  \"qref:v1:5f9f6d3f:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/3.8.1/scala-library-3.8.1.jar\",",
          "  \"qref:v1:260c2c9a:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib_3/SNAPSHOT/mill-libs-javalib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:2027986e:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib-testrunner_3/SNAPSHOT/mill-libs-javalib-testrunner_3-SNAPSHOT.jar\",",
          "  \"qref:v1:d06de6c9:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-kotlinlib-api_3/SNAPSHOT/mill-libs-kotlinlib-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:bcc19440:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/SNAPSHOT/mill-libs-kotlinlib-ksp2-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:c8ce5540:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-groovylib-api_3/SNAPSHOT/mill-libs-groovylib-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:6becdb8a:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-androidlib-databinding_3/SNAPSHOT/mill-libs-androidlib-databinding_3-SNAPSHOT.jar\",",
          "  \"qref:v1:562f66e7:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-scalalib_3/SNAPSHOT/mill-libs-scalalib_3-SNAPSHOT.jar\",",
          "  \"qref:v1:93af0724:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-scalajslib-api_3/SNAPSHOT/mill-libs-scalajslib-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:292c09eb:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-scalanativelib-api_3/SNAPSHOT/mill-libs-scalanativelib-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:ecb22e11:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.25-M23/coursier_2.13-2.1.25-M23.jar\",",
          "  \"qref:v1:a81bad3c:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-archive-cache_2.13/2.1.25-M23/coursier-archive-cache_2.13-2.1.25-M23.jar\",",
          "  \"qref:v1:e5c1e7e6:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.25-M23/coursier-core_2.13-2.1.25-M23.jar\",",
          "  \"qref:v1:553a2fbd:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.25-M23/coursier-cache_2.13-2.1.25-M23.jar\",",
          "  \"qref:v1:62dde61e:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-paths/2.1.25-M23/coursier-paths-2.1.25-M23.jar\",",
          "  \"qref:v1:dcaf515d:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.25-M23/coursier-util_2.13-2.1.25-M23.jar\",",
          "  \"qref:v1:fa7d902d:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.1/versions_2.13-0.5.1.jar\",",
          "  \"qref:v1:4b86a279:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-jvm_2.13/2.1.25-M23/coursier-jvm_2.13-2.1.25-M23.jar\",",
          "  \"qref:v1:ef36c706:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/jline/jline/3.30.6/jline-3.30.6.jar\",",
          "  \"qref:v1:64ba1497:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/fastparse_3/3.1.1/fastparse_3-3.1.1.jar\",",
          "  \"qref:v1:393662d5:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/mainargs_3/0.7.8/mainargs_3-0.7.8.jar\",",
          "  \"qref:v1:b94fcd8d:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/requests_3/0.9.2/requests_3-0.9.2.jar\",",
          "  \"qref:v1:5212d99e:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-api_3/SNAPSHOT/mill-core-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:6d654d22:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0/scala-xml_3-2.4.0.jar\",",
          "  \"qref:v1:9dce2e34:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar\",",
          "  \"qref:v1:b36922f2:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-rpc_3/SNAPSHOT/mill-libs-rpc_3-SNAPSHOT.jar\",",
          "  \"qref:v1:1e292d38:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib-api_3/SNAPSHOT/mill-libs-javalib-api_3-SNAPSHOT.jar\",",
          "  \"qref:v1:b9b79ba1:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/SNAPSHOT/mill-libs-javalib-testrunner-entrypoint-SNAPSHOT.jar\",",
          "  \"qref:v1:10319894:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-util-java11_3/SNAPSHOT/mill-libs-util-java11_3-SNAPSHOT.jar\",",
          "  \"qref:v1:b81c54f7:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-api-java11_3/SNAPSHOT/mill-core-api-java11_3-SNAPSHOT.jar\",",
          "  \"qref:v1:9ca2e086:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-sbt/test-interface/1.0/test-interface-1.0.jar\",",
          "  \"qref:v1:53c369aa:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_3/1.1.1/geny_3-1.1.1.jar\",",
          "  \"qref:v1:0168cd2a:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_3/2.12.0/scala-collection-compat_3-2.12.0.jar\",",
          "  \"qref:v1:5ab06919:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_3/0.11.7/os-lib_3-0.11.7.jar\",",
          "  \"qref:v1:cc3a7106:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_3/4.4.2/upickle_3-4.4.2.jar\",",
          "  \"qref:v1:2d39225b:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits-named-tuples_3/4.4.2/upickle-implicits-named-tuples_3-4.4.2.jar\",",
          "  \"qref:v1:f828b9c1:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.6/pprint_3-0.9.6.jar\",",
          "  \"qref:v1:aad65ed1:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/fansi_3/0.5.1/fansi_3-0.5.1.jar\",",
          "  \"qref:v1:31669051:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-api-daemon_3/SNAPSHOT/mill-core-api-daemon_3-SNAPSHOT.jar\",",
          "  \"qref:v1:93a026a6:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-daemon-server_3/SNAPSHOT/mill-libs-daemon-server_3-SNAPSHOT.jar\",",
          "  \"qref:v1:990451f5:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.7/os-zip-0.11.7.jar\",",
          "  \"qref:v1:0398f896:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_3/4.4.2/ujson_3-4.4.2.jar\",",
          "  \"qref:v1:8389bd85:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_3/4.4.2/upack_3-4.4.2.jar\",",
          "  \"qref:v1:6920e45d:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_3/4.4.2/upickle-implicits_3-4.4.2.jar\",",
          "  \"qref:v1:138fab42:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar\",",
          "  \"qref:v1:95b4b53e:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/custom-local-repo/com/lihaoyi/mill-libs-daemon-client_3/SNAPSHOT/mill-libs-daemon-client_3-SNAPSHOT.jar\",",
          "  \"qref:v1:06bc69bb:/Users/lihaoyi/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_3/4.4.2/upickle-core_3-4.4.2.jar\",",
          "  \"ref:v0:c984eca8:/Users/lihaoyi/Github/mill/out/integration/feature/mill-repositories/testForked.dest/sandbox/run-1/mill-build/compile-resources\"",
          "]"
        )
      )
    }
  }
}
