package millbuild
import mill.*, javalib.*

trait LintUnchecked extends JavaModule {
  def javacOptions = Task { super.javacOptions() ++ Seq("-Xlint:unchecked") }
}
