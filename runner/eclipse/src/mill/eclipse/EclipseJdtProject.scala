package mill.eclipse

import java.nio.file.Path

/**
 *  The object contains the necessary information required to create all Eclipse JDT files for a
 *  specific project.
 *
 *  @param projectName used for creating the ".project" file
 *  @param javaSourceVersion used for creating the Eclipse JDT related preferences
 *  @param javaTargetVersion used for the launcher and creating the Eclipse JDT related preference
 *  @param linkedResources will include source folders outside of the project directory
 *  @param sourceFolders used for creating the ".classpath" file
 *  @param dependentProjectPaths used for creating the ".classpath" file
 *  @param dependentLibraries used for creating the ".classpath" file
 */
final case class EclipseJdtProject(
    projectName: String,
    javaSourceVersion: String,
    javaTargetVersion: String,
    linkedResources: Seq[LinkedResource],
    sourceFolders: Seq[SourceFolder],
    dependentProjectPaths: Seq[Path],
    dependentLibraries: Seq[Library]
)

/**
 *  For source folders outside of the Eclipse JDT Project directory we have to create a linked
 *  resource that will be in turn be used in the ".classpath" file via its name.
 *
 *  @see <a href="https://help.eclipse.org/latest/index.jsp?topic=%2Forg.eclipse.platform.doc.user%2Fconcepts%2Fconcepts-13.htm">Eclipse Reference</a>
 *  @param absolutePath to the linked resources
 *  @param name of the linked resource (by default the folder name) used for the classpath entry
 */
final case class LinkedResource(absolutePath: Path, name: String)

/**
 *  This contains the information about a source folder for the Eclipse JDT Project. We eequire
 *  this information for constructing the ".classpath" information with the correct output
 *  directories.
 *
 *  @param relativePath to the source folder
 *  @param sourceSetName name of this source set, will be used for the output directory
 *  @param isTest whether this contains test code or nor
 *  @param isLinkedResource whether or not this source folder is coming from a linked resource
 */
final case class SourceFolder(
    relativePath: String,
    sourceSetName: String | Null,
    isTest: Boolean,
    isLinkedResource: Boolean
)

/**
 *  This contains the information about a Mill Module library. As this not necessarily has to be a
 *  Jar archive and if it is, there is no garantee that a sources or Javadoc Jar archive exists.
 *
 *  @param jarPath path to the main library (most likely a Jar archive)
 *  @param sourcesJarPath (optional) path to sources archive
 *  @param javadocJarPath (optional) path to Javadoc archive
 */
final case class Library(jarPath: Path, sourcesJarPath: Path | Null, javadocJarPath: Path | Null)
