package mill

class UTestFramework extends utest.runner.Framework {
  override def exceptionStackFrameHighlighter(s: StackTraceElement): Boolean = {
    s.getClassName.startsWith("mill.")
  }
  override def setup(): Unit = {

    os.remove.all(os.pwd / "target" / "workspace")
  }
}
