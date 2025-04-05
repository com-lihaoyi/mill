package mill.contrib.bloop

import mill.api.WorkspaceRoot
import mill.define.Evaluator

/**
 * Usage : `mill mill.contrib.bloop.Bloop/install`
 */
object Bloop extends BloopImpl(
      () => Evaluator.allBootstrapEvaluators.value.value.map(_.asInstanceOf[Evaluator]),
      WorkspaceRoot.workspaceRoot,
      addMillSources = None
    )
