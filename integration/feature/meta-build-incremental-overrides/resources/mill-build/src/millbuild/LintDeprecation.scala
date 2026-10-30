package millbuild

import mill.*, javalib.*, mill.api.opt.*

trait LintDeprecation extends JavaModule {
  def javacOptions = Task { super.javacOptions() ++ Opts("-Xlint:deprecation") }
}
