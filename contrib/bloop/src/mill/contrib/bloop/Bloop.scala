package mill.contrib.bloop

import mill.api.BuildCtx
import mill.eval.Evaluator

/**
 * Usage : `mill mill.contrib.bloop.Bloop/install`
 */
object Bloop extends BloopImpl(
      () => Evaluator.allBootstrapEvaluators.value.value,
      BuildCtx.workspaceRoot,
      addMillSources = None
    )
