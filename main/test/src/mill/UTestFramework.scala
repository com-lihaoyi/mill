package mill

class UTestFramework extends utest.runner.Framework {
  override def exceptionStackFrameHighlighter(s: StackTraceElement) = {
    s.getClassName.startsWith("mill.")
  }
  override def setup() = {

    os.remove.all(os.pwd / 'target / 'workspace)
  }
}
