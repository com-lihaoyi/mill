package mill.contrib

import mill.eval.Evaluator
import mill.contrib.bloop.BloopImpl

@deprecated("Use mill.contrib.bloop.Bloop instead", since = "mill after 0.8.0")
object Bloop extends BloopImpl(Evaluator.currentEvaluator.get, os.pwd)
