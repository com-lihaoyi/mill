/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package mill.kotlinlib

import mill.{T, Task}
import mill.define.{Discover, ExternalModule, Module, Worker}

trait KotlinWorkerModule extends Module {
  def kotlinWorkerManager: Worker[KotlinWorkerManager] = T.worker {
    new KotlinWorkerManagerImpl(T.ctx())
  }
}

object KotlinWorkerModule extends ExternalModule with KotlinWorkerModule {
  override def millDiscover: Discover = Discover[this.type]
}
