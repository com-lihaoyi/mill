/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib

import mill.Task
import mill.define.{Discover, ExternalModule, Module, Worker}

trait KotlinWorkerModule extends Module {
  def kotlinWorkerManager: Worker[KotlinWorkerManager] = Task.Worker {
    new KotlinWorkerManagerImpl(Task.ctx())
  }
}

object KotlinWorkerModule extends ExternalModule with KotlinWorkerModule {
  override def millDiscover: Discover = Discover[this.type]
}
