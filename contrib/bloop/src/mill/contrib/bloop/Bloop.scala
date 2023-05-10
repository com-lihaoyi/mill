package mill.contrib

import mill.eval.Evaluator

/**
 * Usage : `mill mill.contrib.bloop.Bloop/install`
 */
object Bloop extends BloopImpl(() => Evaluator.currentEvaluator.value, os.pwd)
