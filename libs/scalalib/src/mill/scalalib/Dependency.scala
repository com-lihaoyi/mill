package mill.scalalib

import mill.{Task, given}
import mill.api.{Discover, Evaluator, ExternalModule}
import mill.scalalib.dependency.{DependencyUpdatesImpl, Format}
import mill.scalalib.dependency.updates.ModuleDependenciesUpdates

object Dependency extends ExternalModule.Alias(mill.javalib.Dependency)
