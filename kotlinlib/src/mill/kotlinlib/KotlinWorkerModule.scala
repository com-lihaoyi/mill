package mill.kotlinlib

import mill.T
import mill.define.{Discover, ExternalModule, Module, Worker}

trait KotlinWorkerModule extends Module {
  def kotlinWorkerManager: Worker[KotlinWorkerManager] = T.worker {
   new KotlinWorkerManagerImpl(T.ctx())
  }
}

object KotlinWorkerModule extends ExternalModule with KotlinWorkerModule {
  override def millDiscover: Discover[this.type] = Discover[this.type]
}
