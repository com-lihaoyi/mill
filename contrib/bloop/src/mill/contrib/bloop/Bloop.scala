package mill.contrib.bloop

import mill.api.WorkspaceRoot
import mill.define.Evaluator

/**
 * Usage : `mill mill.contrib.bloop.Bloop/install`
 */
object Bloop extends BloopImpl(
      () => mill.runner.api.EvaluatorApi.allBootstrapEvaluators.value.value.collect{case e: Evaluator => e},
      WorkspaceRoot.workspaceRoot,
      addMillSources = None
    )
