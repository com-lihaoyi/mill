package mill.contrib.owaspdependencycheck

import mill.api.Task.Simple as T
import mill.api.{PathRef, Task}
import mill.javalib.JavaModule

/**
 * Java Dependency Check, that adds the resolvedRunMvnDeps path to be scanned in the dependency check.
 */
trait OwaspDependencyCheckJavaModule extends JavaModule, OwaspDependencyCheckModule {
  override def owaspDependencyCheckFiles: T[Seq[PathRef]] = Task {
    super.resolvedRunMvnDeps()
  }
}
