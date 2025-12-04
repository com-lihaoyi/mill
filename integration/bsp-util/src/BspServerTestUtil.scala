package mill.integration

import ch.epfl.scala.bsp4j as b
import com.google.gson.{Gson, GsonBuilder}
import coursier.cache.CacheDefaults
import mill.api.BuildInfo
import mill.bsp.Constants
import org.eclipse.lsp4j as l
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors, ThreadFactory}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

object BspServerTestUtil {

  def bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

  trait TestBuildClient extends b.BuildClient {
    // Whether to check the validity of some messages
    protected def enableAsserts: Boolean = true
    private var gotInvalidMessages0 = false
    def gotInvalidMessages: Boolean = gotInvalidMessages0

    protected def doAssert(condition: Boolean): Unit = {
      if (enableAsserts) {
        if (!condition)
          gotInvalidMessages0 = true
        assert(condition)
      }
    }

    def onBuildTaskProgress(params: b.TaskProgressParams): Unit = {
      doAssert(params.getProgress <= params.getTotal)
    }
  }

  trait DummyBuildClient extends TestBuildClient {
    def onBuildLogMessage(params: b.LogMessageParams): Unit = ()
    def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = ()
    def onBuildShowMessage(params: b.ShowMessageParams): Unit = ()
    def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = ()
    def onBuildTaskFinish(params: b.TaskFinishParams): Unit = ()
    def onBuildTaskStart(params: b.TaskStartParams): Unit = ()
    def onRunPrintStderr(params: b.PrintParams): Unit = ()
    def onRunPrintStdout(params: b.PrintParams): Unit = ()
  }

  val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  def compareWithGsonSnapshot[T: ClassTag](
      value: T,
      snapshotPath: os.Path,
      normalizedLocalValues: Seq[(String, String)] = Nil
  )(using reporter: utest.framework.GoldenFix.Reporter): Unit = {

    def normalizeLocalValues(input: String, inverse: Boolean = false): String =
      normalizedLocalValues.foldLeft(input) {
        case (input0, (from0, to0)) =>
          val (from, to) = if (inverse) (to0, from0) else (from0, to0)
          input0.replace(from, to)
      }.replaceAll("\"javaHome\": \"[^\"]+\"", "\"javaHome\": \"java-home\"")

    val jsonStr = normalizeLocalValues(
      gson.toJson(
        value,
        summon[ClassTag[T]].runtimeClass
      )
    )

    utest.assertGoldenFile(jsonStr, snapshotPath.toNIO)
  }

  def compareLogWithSnapshot(
      log: String,
      snapshotPath: os.Path,
      ignoreLine: String => Boolean = _ => false
  )(using reporter: utest.framework.GoldenFix.Reporter): Unit = {

    val logLines = log
      .linesIterator
      .filterNot(ignoreLine)
      .toVector
      .map(
        _.replaceAll("semanticdbVersion: [0-9.]*", "semanticdbVersion: *")
          .replaceAll("\\d+ msec", "* msec")
          .replaceAll("\\d+ Scala (sources?) to .*\\.\\.\\.", "* Scala $1 to * ...")
          .replaceAll("\\[error\\] [a-zA-Z0-9-_/.]+:2:3:", "[error] *:2:3:")
          .replaceAll("Evaluating [0-9]+ tasks", "Evaluating * tasks")
      )

    utest.assertGoldenFile(
      logLines.mkString(System.lineSeparator()),
      snapshotPath.toNIO
    )
  }

  val bspJsonrpcPool: ExecutorService = Executors.newCachedThreadPool(
    new ThreadFactory {
      val counter = new AtomicInteger
      def newThread(runnable: Runnable): Thread = {
        val t = Thread(runnable, s"mill-bsp-integration-${counter.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    }
  )

  trait MillBuildServer extends b.BuildServer with b.JvmBuildServer
      with b.JavaBuildServer with b.ScalaBuildServer {
    @JsonRequest("millTest/loggingTest")
    def loggingTest(): CompletableFuture[Object]
  }

  def withBspServer[T](
      workspacePath: os.Path,
      millTestSuiteEnv: Map[String, String],
      bspLog: Option[(Array[Byte], Int) => Unit] = None,
      client: TestBuildClient = new DummyBuildClient {}
  )(f: (MillBuildServer, b.InitializeBuildResult) => T): T = {

    val outputOnErrorOnly = System.getenv("CI") != null

    val bspCommand = {
      val bspMetadataFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
      assert(os.exists(bspMetadataFile))
      val contents = os.read(bspMetadataFile)
      assert(
        !contents.contains("--debug"),
        contents.contains(s""""bspVersion":"$bsp4jVersion"""")
      )
      val contentsJson = ujson.read(contents)
      contentsJson("argv").arr.map(_.str)
    }

    val stderr = new ByteArrayOutputStream
    val proc = os.proc(bspCommand).spawn(
      cwd = workspacePath,
      stderr =
        if (bspLog.isDefined || outputOnErrorOnly)
          os.ProcessOutput { (bytes, len) =>
            if (outputOnErrorOnly)
              stderr.write(bytes, 0, len)
            else
              System.err.write(bytes, 0, len)
            for (f <- bspLog)
              f(bytes, len)
          }
        else os.Inherit,
      env = millTestSuiteEnv
    )

    var success = false
    try {
      val launcher = new l.jsonrpc.Launcher.Builder[MillBuildServer]
        .setExecutorService(bspJsonrpcPool)
        .setInput(proc.stdout.wrapped)
        .setOutput(proc.stdin.wrapped)
        .setRemoteInterface(classOf[MillBuildServer])
        .setLocalService(client)
        .setExceptionHandler { t =>
          System.err.println(s"Error during LSP processing: $t")
          t.printStackTrace(System.err)
          l.jsonrpc.RemoteEndpoint.DEFAULT_EXCEPTION_HANDLER.apply(t)
        }
        .create()

      launcher.startListening()

      val buildServer = launcher.getRemoteProxy()

      val initParams = new b.InitializeBuildParams(
        "Mill Integration",
        BuildInfo.millVersion,
        b.Bsp4j.PROTOCOL_VERSION,
        workspacePath.toURI.toASCIIString,
        new b.BuildClientCapabilities(List("java", "scala", "kotlin").asJava)
      )
      // Tell Mill BSP we want semanticdbs
      initParams.setData(
        InitData(
          mill.api.daemon.BuildInfo.semanticDBVersion,
          mill.api.daemon.BuildInfo.semanticDbJavaVersion
        )
      )
      // This seems to be unused by Mill BSP for now, setting it just in case
      initParams.setDataKind("scala")

      val initRes = buildServer.buildInitialize(initParams).get()

      val value =
        try f(buildServer, initRes)
        finally {
          buildServer.buildShutdown().get()
          buildServer.onBuildExit()
        }
      success = true
      assert(
        !client.gotInvalidMessages,
        "Test build client got invalid messages from Mill, see assertions above"
      )
      value
    } finally {
      try {
        proc.stdin.close()
        proc.stdout.close()

        proc.join(30000L)
      } finally {
        if (!success && outputOnErrorOnly) {
          System.err.println(" == BSP server output ==")
          System.err.write(stderr.toByteArray)
          System.err.println(" == end of BSP server output ==")
        }
      }
    }
  }

  lazy val millWorkspace: os.Path = {
    val value = Option(System.getenv("MILL_PROJECT_ROOT")).getOrElse(???)
    os.Path(value)
  }

  def normalizeLocalValuesForTesting(
      workspacePath: os.Path,
      coursierCache: os.Path = os.Path(CacheDefaults.location),
      javaHome: os.Path = os.Path(sys.props("java.home")),
      javaVersion: String = sys.props("java.version")
  ): Seq[(String, String)] =
    Seq(
      workspacePath.toURI.toASCIIString.stripSuffix("/") -> "file:///workspace",
      coursierCache.toURI.toASCIIString -> "file:///coursier-cache/",
      millWorkspace.toURI.toASCIIString -> "file:///mill-workspace/",
      javaHome.toURI.toASCIIString.stripSuffix("/") -> "file:///java-home",
      os.home.toURI.toASCIIString.stripSuffix("/") -> "file:///user-home",
      workspacePath.toString -> "/workspace",
      coursierCache.toString -> "/coursier-cache",
      millWorkspace.toString -> "/mill-workspace",
      os.home.toString -> "/user-home"
    )

  def scalaVersionNormalizedValues(): Seq[(String, String)] = {
    val scala2Version = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    val scala3Version = sys.props.getOrElse("MILL_SCALA_3_NEXT_VERSION", ???)
    val scala2TransitiveSubstitutions = transitiveDependenciesSubstitutions(
      coursierapi.Dependency.of(
        "org.scala-lang",
        "scala-compiler",
        scala2Version
      ),
      _.getModule.getOrganization != "org.scala-lang"
    )
    val scala3TransitiveSubstitutions = transitiveDependenciesSubstitutions(
      coursierapi.Dependency.of(
        "org.scala-lang",
        "scala3-compiler_3",
        scala3Version
      ),
      _.getModule.getOrganization != "org.scala-lang"
    )

    scala2TransitiveSubstitutions ++ scala3TransitiveSubstitutions ++
      Seq(
        scala2Version -> "<scala-version>",
        scala3Version -> "<scala3-version>"
      )
  }

  def kotlinVersionNormalizedValues(): Seq[(String, String)] = {
    val kotlinVersion = sys.props.getOrElse("TEST_KOTLIN_VERSION", ???)
    val kotlinTransitiveSubstitutions = transitiveDependenciesSubstitutions(
      coursierapi.Dependency.of(
        "org.jetbrains.kotlin",
        "kotlin-stdlib",
        kotlinVersion
      ),
      _.getModule.getOrganization != "org.jetbrains.kotlin"
    )
    kotlinTransitiveSubstitutions ++ Seq(kotlinVersion -> "<kotlin-version>")
  }

  def transitiveDependenciesSubstitutions(
      dependency: coursierapi.Dependency,
      filter: coursierapi.Dependency => Boolean
  ): Seq[(String, String)] = {
    val fetchRes = coursierapi.Fetch.create()
      .addDependencies(dependency)
      .fetchResult()
    fetchRes.getDependencies.asScala
      .filter(filter)
      .map { dep =>
        val organization = dep.getModule.getOrganization
        val name = dep.getModule.getName
        val prefix = (organization.split('.') :+ name).mkString("/")
        def basePath(version: String): String =
          s"$prefix/$version/$name-$version"
        basePath(dep.getVersion) -> basePath(s"<$name-version>")
      }
      .toSeq
  }

  // using var-s and null-s for GSON, that is meant to serialize this class
  private case class InitData(
      var semanticdbVersion: String = null,
      var javaSemanticdbVersion: String = null
  )
}
