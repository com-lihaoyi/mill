package mill.api.internal.idea

import mill.api.Segments
import mill.api.internal.JavaModuleApi

final case class ResolvedModule(
    segments: Segments,
    scopedCpEntries: Seq[(path: java.nio.file.Path, scope: Option[String])],
    module: JavaModuleApi,
    pluginClasspath: Seq[java.nio.file.Path],
    scalaOptions: Seq[String],
    scalaCompilerClasspath: Seq[java.nio.file.Path],
    libraryClasspath: Seq[java.nio.file.Path],
    facets: Seq[JavaFacet],
    configFileContributions: Seq[IdeaConfigFile],
    compilerOutput: java.nio.file.Path,
    scalaVersion: Option[String],
    resources: Seq[java.nio.file.Path],
    generatedSources: Seq[java.nio.file.Path],
    allSources: Seq[java.nio.file.Path]
)
