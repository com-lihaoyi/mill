package mill.contrib.vaadin.worker

import com.vaadin.experimental.FeatureFlags
import com.vaadin.flow.server.frontend.scanner.{ClassFinder, FrontendDependenciesScanner}

/**
 * The scanner of the production build, for Flow 25.3.4 and newer: `createScanner`
 * also takes `productionMode`, passed as `true` like the Maven plugin does.
 */
private[worker] object FrontendScanner {
  def create(
      allDependenciesScan: Boolean,
      finder: ClassFinder,
      generateEmbeddableWebComponents: Boolean,
      featureFlags: FeatureFlags,
      reactEnabled: Boolean
  ): FrontendDependenciesScanner =
    new FrontendDependenciesScanner.FrontendDependenciesScannerFactory().createScanner(
      allDependenciesScan,
      finder,
      generateEmbeddableWebComponents,
      featureFlags,
      reactEnabled,
      /* productionMode = */ true
    )
}
