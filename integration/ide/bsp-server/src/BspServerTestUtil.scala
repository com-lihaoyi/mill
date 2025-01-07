package mill.integration

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.{Gson, GsonBuilder}
import coursier.cache.CacheDefaults
import mill.api.BuildInfo
import mill.bsp.Constants
import org.eclipse.{lsp4j => l}

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object BspServerTestUtil {

  val updateSnapshots = false

  def bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

  trait DummyBuildClient extends b.BuildClient {
    def onBuildLogMessage(params: b.LogMessageParams): Unit = ()
    def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = ()
    def onBuildShowMessage(params: b.ShowMessageParams): Unit = ()
    def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = ()
    def onBuildTaskFinish(params: b.TaskFinishParams): Unit = ()
    def onBuildTaskProgress(params: b.TaskProgressParams): Unit = ()
    def onBuildTaskStart(params: b.TaskStartParams): Unit = ()
    def onRunPrintStderr(params: b.PrintParams): Unit = ()
    def onRunPrintStdout(params: b.PrintParams): Unit = ()
  }

  object DummyBuildClient extends DummyBuildClient

  val gson: Gson = new GsonBuilder().setPrettyPrinting().create()
  def compareWithGsonSnapshot[T: ClassTag](
      value: T,
      snapshotPath: os.Path,
      normalizedLocalValues: Seq[(String, String)] = Nil
  ): Unit = {

    def normalizeLocalValues(input: String, inverse: Boolean = false): String =
      normalizedLocalValues.foldLeft(input) {
        case (input0, (from0, to0)) =>
          val (from, to) = if (inverse) (to0, from0) else (from0, to0)
          input0.replace(from, to)
      }

    // This can be false only when generating test data for the first time.
    // In that case, updateSnapshots needs to be true, so that we write test data on disk.
    val exists = os.exists(snapshotPath)
    val expectedValueOpt = Option.when(exists) {
      gson.fromJson(
        normalizeLocalValues(os.read(snapshotPath), inverse = true),
        implicitly[ClassTag[T]].runtimeClass
      )
    }

    if (!expectedValueOpt.contains(value)) {
      lazy val jsonStr = gson.toJson(
        value,
        implicitly[ClassTag[T]].runtimeClass
      )
      lazy val expectedJsonStr = expectedValueOpt match {
        case Some(v) => gson.toJson(v, implicitly[ClassTag[T]].runtimeClass)
        case None => ""
      }
      if (updateSnapshots) {
        System.err.println(if (exists) s"Updating $snapshotPath" else s"Writing $snapshotPath")
        os.write.over(snapshotPath, normalizeLocalValues(jsonStr), createFolders = true)
      } else {
        Predef.assert(
          false,
          if (exists) {
            val diff = os.call((
              "git",
              "diff",
              os.temp(jsonStr, suffix = s"${snapshotPath.last}-jsonStr"),
              os.temp(expectedJsonStr, suffix = s"${snapshotPath.last}-expectedJsonStr")
            ))
            s"""Error: value differs from snapshot in $snapshotPath
               |
               |You might want to set BspServerTestUtil.updateSnapshots to true,
               |run this test again, and commit the updated test data files.
               |
               |$diff
               |""".stripMargin
          } else s"Error: no snapshot found at $snapshotPath"
        )
      }
    } else if (updateSnapshots) {
      // Snapshot on disk might need to be updated anyway, if normalizedLocalValues changed
      // and new strings should be replaced
      val obtainedJsonStr = normalizeLocalValues(
        gson.toJson(
          value,
          implicitly[ClassTag[T]].runtimeClass
        )
      )
      val expectedJsonStr = os.read(snapshotPath)
      if (obtainedJsonStr != expectedJsonStr) {
        System.err.println(s"Updating $snapshotPath")
        os.write.over(snapshotPath, obtainedJsonStr)
      }
    }
  }

  val bspJsonrpcPool: ExecutorService = Executors.newCachedThreadPool(
    new ThreadFactory {
      val counter = new AtomicInteger
      def newThread(runnable: Runnable): Thread = {
        val t = new Thread(runnable, s"mill-bsp-integration-${counter.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    }
  )

  trait MillBuildServer extends b.BuildServer with b.JvmBuildServer
      with b.JavaBuildServer with b.ScalaBuildServer

  def withBspServer[T](
      workspacePath: os.Path,
      millTestSuiteEnv: Map[String, String]
  )(f: (MillBuildServer, b.InitializeBuildResult) => T): T = {

    val bspMetadataFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
    assert(os.exists(bspMetadataFile))
    val contents = os.read(bspMetadataFile)
    assert(
      !contents.contains("--debug"),
      contents.contains(s""""bspVersion":"$bsp4jVersion"""")
    )

    val outputOnErrorOnly = System.getenv("CI") != null

    val contentsJson = ujson.read(contents)
    val bspCommand = contentsJson("argv").arr.map(_.str)
    val stderr = new ByteArrayOutputStream
    val proc = os.proc(bspCommand).spawn(
      cwd = workspacePath,
      stderr =
        if (outputOnErrorOnly)
          os.ProcessOutput { (bytes, len) =>
            stderr.write(bytes, 0, len)
          }
        else os.Inherit,
      env = millTestSuiteEnv
    )

    val client: b.BuildClient = DummyBuildClient

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

      val initRes = buildServer.buildInitialize(
        new b.InitializeBuildParams(
          "Mill Integration",
          BuildInfo.millVersion,
          b.Bsp4j.PROTOCOL_VERSION,
          workspacePath.toNIO.toUri.toASCIIString,
          new b.BuildClientCapabilities(List("java", "scala", "kotlin").asJava)
        )
      ).get()

      val value =
        try f(buildServer, initRes)
        finally buildServer.buildShutdown().get()
      success = true
      value
    } finally {
      proc.stdin.close()
      proc.stdout.close()

      proc.join(30000L)

      if (!success && outputOnErrorOnly) {
        System.err.println(" == BSP server output ==")
        System.err.write(stderr.toByteArray)
        System.err.println(" == end of BSP server output ==")
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
      workspacePath.toNIO.toUri.toASCIIString.stripSuffix("/") -> "file:///workspace",
      coursierCache.toNIO.toUri.toASCIIString -> "file:///coursier-cache/",
      millWorkspace.toNIO.toUri.toASCIIString -> "file:///mill-workspace/",
      javaHome.toNIO.toUri.toASCIIString.stripSuffix("/") -> "file:///java-home",
      ("\"" + javaVersion + "\"") -> "\"<java-version>\"",
      workspacePath.toString -> "/workspace",
      coursierCache.toString -> "/coursier-cache",
      millWorkspace.toString -> "/mill-workspace"
    )
}
