package mill.contrib.bloop

import mill.eval.Evaluator
import mill.api.WorkspaceRoot

/**
 * Usage : `mill mill.contrib.bloop.Bloop/install`
 */
object Bloop extends BloopImpl(
      () => Evaluator.allBootstrapEvaluators.value.value,
      BuildCtx.workspaceRoot,
      addMillSources = None
    )
