package mill.eclipse

import scala.xml.{Elem, MetaData, NodeSeq, Null, UnprefixedAttribute}

object EclipseJdtUtils {

  /**
   *  Create the ".project" file content for normal, non-JDT projects
   *
   *  @param projectName name of the Eclipse project
   *  @return the XML content of the file
   */
  def createNormalProjectFileContent(projectName: String): Elem =
    createProjectFileContent(projectName, false, Seq.empty[LinkedResource])

  /**
   *  Create the ".project" file content for JDT projects
   *
   *  @param projectName name of the Eclipse project
   *  @param linkedResources used to create the links with the correct name and path
   *  @return the XML content of the file
   */
  def createJdtProjectFileContent(
      projectName: String,
      linkedResources: Seq[LinkedResource]
  ): Elem = createProjectFileContent(projectName, true, linkedResources)

  /**
   * This creates the XML content for the ".project" file.
   *
   * @see <a href="https://help.eclipse.org/latest/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Freference%2Fmisc%2Fproject_description_file.html">Eclipse Reference</a>
   * @param projectName name of the Eclipse project
   * @param isJdtProject used for setting the build command / nature of JDT
   * @param linkedResources used to create the links with the correct name and path
   * @return the XML content of the file
   */
  def createProjectFileContent(
      projectName: String,
      isJdtProject: Boolean,
      linkedResources: Seq[LinkedResource]
  ): Elem = {
    <projectDescription>
      <name>{projectName}</name>
      <comment></comment>
      <projects>
      </projects>
      <buildSpec>
        {
      if (isJdtProject) {
        <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments>
            </arguments>
          </buildCommand>
      } else {
        NodeSeq.Empty
      }
    }
      </buildSpec>
      <natures>
        {
      if (isJdtProject) {
        <nature>org.eclipse.jdt.core.javanature</nature>
      } else {
        NodeSeq.Empty
      }
    }
      </natures>
      <linkedResources>
        {for (linkedResource <- linkedResources) yield getLink(linkedResource)}
      </linkedResources>
    </projectDescription>
  }

  /**
   *  Get the ".project" file entry for a linked resource to a specific source folder outside of
   *  the Eclipse JDT Project directory
   *
   *  @param linkedResource used to create the link with the correct name and path
   *  @return the XML entry
   */
  private def getLink(linkedResource: LinkedResource): Elem = {
    <link>
      <name>{linkedResource.name}</name>
      <type>2</type>
      <location>{linkedResource.absolutePath.toString}</location>
    </link>
  }

  /**
   *  This generates the XML content for the ".classpath" file based on the adjusted information
   *  from a [[EclipseJdtProject]] object. Adjusted because the dependent project names are not yet
   *  final when such an object is created.
   *
   *  @param javaTargetVersion used for setting the launcher container version
   *  @param sourceFolders used for creating the entries and the correct output directories
   *  @param dependentProjectName used for creating dependency entries
   *  @param dependentLibraries used for creating dependency entries (optional sources / javadoc)
   *  @return the XML content of the file
   */
  def createClasspathFileContent(
      javaTargetVersion: String,
      sourceFolders: Seq[SourceFolder],
      dependentProjectName: Seq[String],
      dependentLibraries: Seq[Library]
  ): Elem = {
    <classpath>
      <classpathentry kind="output" path="bin" />

      {for (sourceFolder <- sourceFolders) yield getSourceClasspathEntry(sourceFolder)}
      {for (name <- dependentProjectName) yield getProjectClasspathEntry(name)}
      {for (library <- dependentLibraries) yield getLibraryClasspathEntry(library)}
      {getJavaVersionClasspathEntry(javaTargetVersion)}
    </classpath>
  }

  /**
   *  Get the ".classpath" entry for a source folder, always relative to the project directory.
   *  Base on whether or not this is a source folder containing only test code, its output is a
   *  different directory.
   *
   *  @param sourceFolder used to create the source folder entry
   *  @return the XML entry
   */
  private def getSourceClasspathEntry(sourceFolder: SourceFolder): Elem = {
    // In order to not collide with output folder names we create unique ones for every single
    // source folder based on its relative path (which are unique as well).
    val outputName = sourceFolder.relativePath.replace("\\", "-").replace("/", "-")

    if (sourceFolder.isTest) {
      <classpathentry kind="src"
                        path={sourceFolder.relativePath}
                        output={s"bin/$outputName-classes"}>
          <attributes>
            <attribute name="test" value="true" />
          </attributes>
        </classpathentry>
    } else {
      <classpathentry kind="src"
                        path={sourceFolder.relativePath}
                        output={s"bin/$outputName-classes"} />
    }
  }

  /**
   *  Get the ".classpath" entry for a dependent project. There is no actual path here since they
   *  will be part of the same Eclipse workspace and are therefore residing next to each other
   *  despite them possibly residing in a hierarchical form in Mill itself.
   *
   *  @param projectName used to create the connection
   *  @return the XML entry
   */
  private def getProjectClasspathEntry(projectName: String): Elem = {
    <classpathentry kind="src"
                    combineaccessrules="false"
                    path={"/" + projectName} />
  }

  /**
   *  Get the ".classpath" entry for a dependent library. Since there is no companion Eclipse
   *  plug-in to this generator, this will use the absolute path. The dependency library does not
   *  necessarily need to be a JAR archive, it can also be a folder of ".class" files.
   *  This might not happen in the context of the Mill Build Tool but we won't differentiate to
   *  provide forward support in case this happens.
   *
   *  @param library containing the necessary information for the Jar, optionally sources / Javadoc
   *  @return the XML entry
   */
  private def getLibraryClasspathEntry(library: Library): Elem = {
    if (library.sourcesJarPath != null) {
      <classpathentry kind="lib"
                        path={library.jarPath.toString}
                        sourcepath={library.sourcesJarPath.toString}>
          {
        if (library.javadocJarPath != null) {
          <attributes>
              <attribute name="javadoc_location"
                         value={"jar:file:" + library.javadocJarPath.toString + "!/"} />
            </attributes>
        } else {
          NodeSeq.Empty
        }
      }
        </classpathentry>
    } else {
      <classpathentry kind="lib"
                        path={library.jarPath.toString}>
          {
        if (library.javadocJarPath != null) {
          <attributes>
              <attribute name="javadoc_location"
                         value={"jar:file:" + library.javadocJarPath.toString + "!/"} />
            </attributes>
        } else {
          NodeSeq.Empty
        }
      }
        </classpathentry>
    }
  }

  /**
   *  Get the ".classpath" entry for the launcher container provided by Eclipse JDT. This is
   *  mandatory for when classes of this project are launched.
   *  This must be the Java target version and not the source level compliance version!
   *
   *  @param javaTargetVersion used to identify the VM inside the Eclipse IDE configuration
   *  @return the XML entry
   */
  private def getJavaVersionClasspathEntry(javaTargetVersion: String): Elem = {
    <classpathentry kind="con"
                      path= {
      "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-" + javaTargetVersion
    } />
  }

  /**
   *  Creates the content for the ".settings/org.eclipse.core.resources.prefs" file. This is the
   *  standard configuration, users can later change / enhance this from within Eclipse itself.
   *
   *  This is the Java Properties file format, the content is the default set when a project is
   *  created from within Eclipse.
   */
  def getOrgEclipseCoreResourcesPrefsContent: String = {
    "eclipse.preferences.version=1\n" +
      "encoding/<project>=UTF-8"
  }

  /**
   *  Creates the content for the ".settings/org.eclipse.jdt.core.prefs" file.
   *
   *  This is the Java Properties file format, the content is the default set when a Java project
   *  is created from within Eclipse.
   *
   *  @param javaSourceVersion used for setting the compiler source / compliance version
   *  @param javaTargetVersion used for setting the compiler target version
   */
  def getOrgEclipseJdtCorePrefsContent(
      javaSourceVersion: String,
      javaTargetVersion: String
  ): String = {
    "eclipse.preferences.version=1\n" +
      s"org.eclipse.jdt.core.compiler.codegen.targetPlatform=$javaTargetVersion\n" +
      s"org.eclipse.jdt.core.compiler.compliance=$javaSourceVersion\n" +
      "org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures=disabled\n" +
      "org.eclipse.jdt.core.compiler.problem.forbiddenReference=warning\n" +
      "org.eclipse.jdt.core.compiler.problem.reportPreviewFeatures=ignore\n" +
      "org.eclipse.jdt.core.compiler.release=disabled\n" +
      s"org.eclipse.jdt.core.compiler.source=$javaSourceVersion"
  }
}
