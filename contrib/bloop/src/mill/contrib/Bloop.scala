package mill.contrib

import bloop._
import mill.eval.Evaluator

@deprecated("Use mill.contrib.bloop.Bloop instead", since = "mill 0.11.1")
object Bloop extends BloopImpl(() => Evaluator.currentEvaluator.value, os.pwd)
