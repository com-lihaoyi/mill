package mill.contrib

import mill.eval.Evaluator
import os.pwd
import mill.contrib.bloop.BloopImpl

/**
  * Usage : `mill mill.contrib.Bloop/install`
  */
object Bloop extends BloopImpl(Evaluator.currentEvaluator.get, pwd)
