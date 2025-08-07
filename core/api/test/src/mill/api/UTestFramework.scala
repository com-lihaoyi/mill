package mill.api

class UTestFramework extends utest.runner.Framework {
  override def exceptionStackFrameHighlighter(s: StackTraceElement): Boolean = {
    s.getClassName.startsWith("mill.")
  }
  override def setup(): Unit = {
    mill.api.SystemStreamsUtils.setTopLevelSystemStreamProxy() // force initialization

    os.remove.all(os.pwd / "target/workspace")
  }

  override def formatTruncateHeight: Int = 1000
}
