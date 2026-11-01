package millbuild

import mill.*, javalib.*, mill.api.opt.*

trait LintUnchecked extends JavaModule {
  def javacOptions = Task { super.javacOptions() ++ Opts("-Xlint:unchecked") }
}
