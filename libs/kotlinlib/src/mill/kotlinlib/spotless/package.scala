package mill.kotlinlib

import mill.define.ExternalModule

package object spotless extends ExternalModule.Alias(mill.scalalib.spotless.SpotlessModule) {

  type SpotlessModule = mill.scalalib.spotless.SpotlessModule

  type SpotlessWorkerModule = mill.scalalib.spotless.SpotlessWorkerModule

  type WithSpotlessWorker = mill.scalalib.spotless.WithSpotlessWorker

  type Format = mill.scalalib.spotless.Format
  val Format = mill.scalalib.spotless.Format
}
