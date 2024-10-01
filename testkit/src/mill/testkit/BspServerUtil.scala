package mill.testkit

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.{Gson, GsonBuilder}
import coursier.cache.CacheDefaults
import mill.api.BuildInfo
import mill.bsp.Constants
import org.eclipse.{lsp4j => l}

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object BspServerUtil {

  val updateFixtures = false

  lazy val bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

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

  lazy val dummyBuildClient: b.BuildClient = new DummyBuildClient {}

  val gson: Gson = new GsonBuilder().setPrettyPrinting().create()
  def compareWithGsonFixture[T: ClassTag](
      value: T,
      fixturePath: os.Path,
      replaceAll: Seq[(String, String)] = Nil
  ): Unit = {

    def doReplaceAll(input: String, inverse: Boolean = false): String =
      replaceAll.foldLeft(input) {
        case (input0, (from0, to0)) =>
          val (from, to) = if (inverse) (to0, from0) else (from0, to0)
          input0.replace(from, to)
      }

    val exists = os.exists(fixturePath)
    val readOpt = Option.when(exists) {
      val bytes = os.read.bytes(fixturePath)
      gson.fromJson(
        doReplaceAll(new String(bytes), inverse = true),
        implicitly[ClassTag[T]].runtimeClass
      )
    }

    if (!readOpt.contains(value))
      if (updateFixtures) {
        System.err.println(if (exists) s"Updating $fixturePath" else s"Writing $fixturePath")
        val jsonStr = gson.toJson(
          value,
          implicitly[ClassTag[T]].runtimeClass
        )
        os.write.over(fixturePath, doReplaceAll(jsonStr), createFolders = true)
      } else
        Predef.assert(
          false,
          if (exists) s"Error: value differs from fixture at $fixturePath"
          else s"Error: no fixture found at $fixturePath"
        )
    else if (updateFixtures) {
      // Fixture on disk might need to be updated anyway, if replaceAll changed
      // and new strings should be replaced
      val jsonStr = doReplaceAll(
        gson.toJson(
          value,
          implicitly[ClassTag[T]].runtimeClass
        )
      )
      val onDiskJsonStr = os.read(fixturePath)
      if (jsonStr != onDiskJsonStr) {
        System.err.println(s"Updating $fixturePath")
        os.write.over(fixturePath, jsonStr)
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
      millTestSuiteEnv: Map[String, String],
      client: b.BuildClient = dummyBuildClient
  )(f: (MillBuildServer, b.InitializeBuildResult) => T): T = {

    val jsonFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
    assert(os.exists(jsonFile))
    val contents = os.read(jsonFile)
    assert(
      !contents.contains("--debug"),
      contents.contains(s""""bspVersion":"${bsp4jVersion}"""")
    )

    val contentsJson = ujson.read(contents)
    val bspCommand = contentsJson("argv").arr.map(_.str)
    val proc = os.proc(bspCommand).spawn(
      cwd = workspacePath,
      stderr = os.Inherit,
      env = millTestSuiteEnv
    )

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
          new b.BuildClientCapabilities(List("scala", "java", "kotlin").asJava)
        )
      ).get()

      f(buildServer, initRes)
    } finally {
      proc.stdin.close()
      proc.stdout.close()

      proc.join(2000L)
    }
  }

  lazy val millWorkspace: os.Path = {
    val value = Option(System.getenv("MILL_PROJECT_ROOT")).getOrElse(???)
    os.Path(value)
  }

  def replaceAllValues(
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
