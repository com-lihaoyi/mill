package mill.rpc

import mill.api.daemon.Logger
import upickle.default.ReadWriter
import utest.*

import java.util.concurrent.ArrayBlockingQueue

object MillRpcTests extends TestSuite {
  class TestException extends Exception

  case class Initialize(meaning: Int) derives ReadWriter

  sealed trait ClientToServer extends MillRpcMessage derives ReadWriter
  object ClientToServer {
    case object Ping extends ClientToServer, MillRpcMessage.NoResponse

    case object ThrowAnException extends ClientToServer, MillRpcMessage.NoResponse

    case class Echo(what: String) extends ClientToServer {
      override type Response = String
    }

    case class WithExtraInfoRequest(what: String) extends ClientToServer {
      override type Response = Seq[String]
    }
  }

  sealed trait ServerToClient extends MillRpcMessage derives ReadWriter
  object ServerToClient {
    case class GiveMeExtraInfo(onWhat: String) extends ServerToClient {
      override type Response = String
    }
  }

  class Ref[A](@volatile var value: A) {
    override def toString: String = s"Ref($value)"

    def update(f: A => A): Unit = value = f(value)
  }

  case class ReceivedServerMessage(requestId: MillRpcRequestId, message: ClientToServer)

  def makeServer(
      transport: MillRpcWireTransport,
      initializeRef: Ref[Option[Initialize]],
      messagesRef: Ref[Vector[ReceivedServerMessage]]
  ) =
    MillRpcServer.create[Initialize, ClientToServer, ServerToClient]("testServer", transport) {
      (initialize, log, stdout, stderr, serverToClient) =>
        log.info("info from server")
        log.debug("debug from server")
        log.warn("warn from server")
        log.error("error from server")
        log.ticker("ticker from server")

        stdout.println("stdout from server")
        stderr.println("stderr from server")

        initializeRef.value = Some(initialize)

        (requestId, message) => {
          messagesRef.update(_ :+ ReceivedServerMessage(requestId, message))

          message match {
            case ClientToServer.Ping => ().asInstanceOf[message.Response]
            case ClientToServer.ThrowAnException => throw TestException()
            case ClientToServer.Echo(what) => what.asInstanceOf[message.Response]
            case ClientToServer.WithExtraInfoRequest(what) =>
              val info1 = serverToClient(requestId, ServerToClient.GiveMeExtraInfo(what))
              val info2 = serverToClient(requestId, ServerToClient.GiveMeExtraInfo(s"$what again"))
              Seq(info1, info2).asInstanceOf[message.Response]
          }
        }
    }

  case class ReceivedClientMessage(requestId: MillRpcRequestId, message: ServerToClient)

  case class LogItem(level: String, message: String) {
    def has(level: String, needle: String): Boolean =
      this.level == level && message.contains(needle)
  }

  def makeLogger(loggerRef: Ref[Vector[LogItem]]) = {
    new Logger.Actions {
      override def info(message: String): Unit = handle(LogItem("info", message))
      override def debug(message: String): Unit = handle(LogItem("debug", message))
      override def warn(message: String): Unit = handle(LogItem("warn", message))
      override def error(message: String): Unit = handle(LogItem("error", message))
      override def ticker(message: String): Unit = handle(LogItem("ticker", message))

      private def handle(item: LogItem): Unit = {
        loggerRef.update(_ :+ item)
        println(s"[CLIENT LOG: ${item.level}] ${item.message}")
      }
    }
  }

  def makeClient(
      initialize: Initialize,
      transport: MillRpcWireTransport,
      log: Logger.Actions,
      messagesRef: Ref[Vector[ReceivedClientMessage]],
      stdoutRef: Ref[Vector[RpcConsole.Message]],
      stderrRef: Ref[Vector[RpcConsole.Message]]
  ) = {
    def stdoutHandler(msg: RpcConsole.Message): Unit = {
      stdoutRef.update(_ :+ msg)
      RpcConsole.stdoutHandler(msg)
    }

    def stderrHandler(msg: RpcConsole.Message): Unit = {
      stderrRef.update(_ :+ msg)
      RpcConsole.stderrHandler(msg)
    }

    MillRpcClient.create[Initialize, ClientToServer, ServerToClient](
      initialize,
      transport,
      log,
      stdout = stdoutHandler,
      stderr = stderrHandler
    ) {
      (requestId, msg) =>
        {
          messagesRef.update(_ :+ ReceivedClientMessage(requestId, msg))

          msg match {
            case ServerToClient.GiveMeExtraInfo(what) =>
              s"Extra $what for $requestId".asInstanceOf[msg.Response]
          }
        }
    }
  }

  override def tests: Tests = Tests {
    test("rpc") {
      val serverToClientQueue = ArrayBlockingQueue[String](100)
      val clientToServerQueue = ArrayBlockingQueue[String](100)
      val serverToClientTransport = MillRpcWireTransport.ViaBlockingQueues(
        inQueue = clientToServerQueue,
        outQueue = serverToClientQueue
      )
      val clientToServerTransport = MillRpcWireTransport.ViaBlockingQueues(
        inQueue = serverToClientQueue,
        outQueue = clientToServerQueue
      )
      val initializeRef = Ref(Option.empty[Initialize])
      val serverMessagesRef = Ref(Vector.empty[ReceivedServerMessage])
      val clientMessagesRef = Ref(Vector.empty[ReceivedClientMessage])
      val loggerRef = Ref(Vector.empty[LogItem])
      val stdoutRef = Ref(Vector.empty[RpcConsole.Message])
      val stderrRef = Ref(Vector.empty[RpcConsole.Message])
      val server = makeServer(clientToServerTransport, initializeRef, serverMessagesRef)
      val initialize = Initialize(42)
      val client = makeClient(
        initialize,
        serverToClientTransport,
        makeLogger(loggerRef),
        clientMessagesRef,
        stdoutRef = stdoutRef,
        stderrRef = stderrRef
      )
      val serverThread = Thread(() => server.run(), "mill-rpc-server")

      def id(s: String) =
        MillRpcRequestId.fromString(s).getOrElse(throw new IllegalArgumentException(s))

      try {
        serverThread.start()
        assertEventually(initializeRef.value.contains(initialize))

        client(ClientToServer.Ping)
        assert(serverMessagesRef.value.contains(ReceivedServerMessage(
          id("c0"),
          ClientToServer.Ping
        )))

        // We need to have initiated at least one request from client for it to read data from the server
        {
          val logged = loggerRef.value
          assert(logged.exists(_.has("info", "info from server")))
          assert(logged.exists(_.has("debug", "debug from server")))
          assert(logged.exists(_.has("warn", "warn from server")))
          assert(logged.exists(_.has("error", "error from server")))
          assert(logged.exists(_.has("ticker", "ticker from server")))

          val stdout = stdoutRef.value
          assert(stdout.contains(RpcConsole.Message.Print("stdout from server\n")))

          val stderr = stderrRef.value
          assert(stderr.contains(RpcConsole.Message.Print("stderr from server\n")))
        }

        {
          val msg = ClientToServer.Echo("hello world")
          val response = client(msg)
          assert(response == "hello world")
          assert(serverMessagesRef.value.contains(ReceivedServerMessage(id("c1"), msg)))
        }

        def testWithExtraInfo(what: String, reqId: String): Unit = {
          val msg = ClientToServer.WithExtraInfoRequest(what)
          val response = client(msg)
          assert(serverMessagesRef.value.contains(ReceivedServerMessage(id(reqId), msg)))
          assert(clientMessagesRef.value.contains(ReceivedClientMessage(
            id(s"$reqId:s0"),
            ServerToClient.GiveMeExtraInfo(what)
          )))
          assert(clientMessagesRef.value.contains(ReceivedClientMessage(
            id(s"$reqId:s1"),
            ServerToClient.GiveMeExtraInfo(s"$what again")
          )))
          assert(response == Seq(s"Extra $what for $reqId:s0", s"Extra $what again for $reqId:s1"))
        }

        testWithExtraInfo("apple", "c2")
        testWithExtraInfo("box", "c3")
        testWithExtraInfo("pear", "c4")

        {
          val ex = assertThrows[RpcThrowable](client(ClientToServer.ThrowAnException))
          assert(serverMessagesRef.value.contains(ReceivedServerMessage(
            id("c5"),
            ClientToServer.ThrowAnException
          )))
          assert(ex.className == classOf[TestException].getCanonicalName)
        }

        // Things still work
        testWithExtraInfo("banana", "c6")
      } finally {
        serverToClientTransport.close()
        clientToServerTransport.close()
        serverThread.interrupt()
      }
    }
  }
}
