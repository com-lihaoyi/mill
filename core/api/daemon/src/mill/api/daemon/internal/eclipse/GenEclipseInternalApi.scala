package mill.api.daemon.internal.eclipse

import mill.api.daemon.internal.TaskApi

/**
 *  For Eclipse we have to get the information for every Mill Module despite the fact that some
 *  will be aggregated together into one Eclipse JDT project. This will be the case for Test
 *  Modules that will be merged together with their parent (production code) Java Module if
 *  possible.
 */
trait GenEclipseInternalApi {
  private[mill] def genEclipseModuleInformation(): TaskApi[ResolvedModule]
}
