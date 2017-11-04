package forge

class UTestFramework extends utest.runner.Framework {
  override def exceptionStackFrameHighlighter(s: StackTraceElement) = {
    s.getClassName.startsWith("forge.")
  }
  override def setup() = {
    import ammonite.ops._
    rm(pwd / 'target / 'workspace)
  }
}
