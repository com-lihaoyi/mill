package mill.api.daemon.internal.eclipse

import mill.api.daemon.Segments
import mill.api.daemon.internal.JavaModuleApi

import java.nio.file.Path

/**
 *  The relevant information for Eclipse based on a resolved module. This does not intentially mean that this modules
 *  will be a separate Eclipse project or part of another one. But in both cases it contains the necessarz information
 *  to either create a standalone Eclipse project or be merged with an existing one.
 *
 *  We use paths for all the module dependencies since at the point of creating an object of this class it is not final
 *  what the name is. Also, in case modules have the same name, it will be later changed inside the generator as no two
 *  projects insidea Eclipse workspace can have the same name!
 *
 *  @param segments ???
 *  @param module the actual Mill module that will be used to link this in the Eclipse files generator
 *  @param allSources paths to all source + resources folders (includes generated source)
 *  @param allModuleDependencies paths to all module dependencies, ignoring compile / runtime
 *  @param allLibraryDependencies paths to all libary dependencies, ignoreing compile / runtime
 */
final case class ResolvedModule(
    segments: Segments,
    module: JavaModuleApi,
    allSources: Seq[Path],
    allModuleDependencies: Seq[Path],
    allLibraryDependencies: Seq[Path]
)
