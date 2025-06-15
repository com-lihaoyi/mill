package mill.define

class UTestFramework extends utest.runner.Framework {
  override def exceptionStackFrameHighlighter(s: StackTraceElement): Boolean = {
    s.getClassName.startsWith("mill.")
  }
  override def setup(): Unit = {
    mill.define.SystemStreams.setTopLevelSystemStreamProxy() // force initialization

    os.remove.all(os.pwd / "target/workspace")
  }
}
