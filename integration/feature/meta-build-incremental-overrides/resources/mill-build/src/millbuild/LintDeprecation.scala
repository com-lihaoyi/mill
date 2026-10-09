package millbuild
import mill.*, javalib.*

trait LintDeprecation extends JavaModule {
  def javacOptions = Task { super.javacOptions() ++ Seq("-Xlint:deprecation") }
}
