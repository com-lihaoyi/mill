package millbuild

import mill.*
import mill.api.Task

trait MyModule extends Module {
  def originalTaskDependency = Task {
    sys.error("Original task dependency ran")
    0
  }

  def originalTask = Task {
    originalTaskDependency()
    sys.error("original task ran")
    0
  }
}
