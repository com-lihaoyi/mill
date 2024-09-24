package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}

object MultilinePromptLoggerTests extends TestSuite {

  val tests = Tests {
    test {
      val baos = new ByteArrayOutputStream()
      val baosOut = new PrintStream(new ProxyStream.Output(baos, ProxyStream.OUT))
      val baosErr = new PrintStream(new ProxyStream.Output(baos, ProxyStream.ERR))
      val logger = new MultilinePromptLogger(
        colored = false,
        enableTicker = true,
        infoColor = fansi.Attrs.Empty,
        errorColor = fansi.Attrs.Empty,
        systemStreams0 = new SystemStreams(baosOut, baosErr, System.in),
        debugEnabled = false,
        titleText = "TITLE",
        terminfoPath = os.temp()
      )

//      logger.
    }
  }
}
