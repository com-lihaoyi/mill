package mill.integration

object GenEclipseUtils {

  /** Get the current running Java version that is used for */
  private def getJavaVersion: Int = Runtime.version().feature()

  /** Get the number of occurrences of a substring inside a string */
  private def getOccurrences(src: String, subString: String): Int =
    src.sliding(subString.length).count(slide => slide == subString)

  /**
   *  Checks for the standard ".settings/org.eclipse.core.resources.prefs" file and its content
   *  generated correctly.
   */
  def checkOrgEclipseCoreResourcesPrefs(projectPath: os.Path): Unit = {
    val generatedFile = projectPath / ".settings" / "org.eclipse.core.resources.prefs"
    assert(os.exists(generatedFile))

    val lines = os.read.lines(generatedFile)
    assert(lines.contains("eclipse.preferences.version=1"))
    assert(lines.contains("encoding/<project>=UTF-8"))
  }

  /**
   *  Checks for the standard ".settings/org.eclipse.jdt.core.prefs" file and its content generated
   *  correctly.
   */
  def checkOrgEclipseJdtCorePrefs(projectPath: os.Path): Unit = {
    val generatedFile = projectPath / ".settings" / "org.eclipse.jdt.core.prefs"
    assert(os.exists(generatedFile))

    val javaVersion = getJavaVersion
    val lines = os.read.lines(generatedFile)
    assert(lines.contains(s"org.eclipse.jdt.core.compiler.codegen.targetPlatform=$javaVersion"))
    assert(lines.contains(s"org.eclipse.jdt.core.compiler.compliance=$javaVersion"))
    assert(lines.contains("org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures=disabled"))
    assert(lines.contains("org.eclipse.jdt.core.compiler.problem.forbiddenReference=warning"))
    assert(lines.contains("org.eclipse.jdt.core.compiler.problem.reportPreviewFeatures=ignore"))
    assert(lines.contains("org.eclipse.jdt.core.compiler.release=disabled"))
    assert(lines.contains(s"org.eclipse.jdt.core.compiler.source=$javaVersion"))
  }

  /**
   *  Checks for the standard ".project" file and its content generated correctly. Optionally check
   *  for linked resources, otherwise check none exists.
   */
  def checkProjectFile(projectPath: os.Path, linkedResourceNames: Seq[String]): Unit = {
    val generatedFile = projectPath / ".project"
    assert(os.exists(generatedFile))

    val workspaceFolderName = projectPath.toNIO.getFileName.toString
    val fileContent = os.read.lines(generatedFile).mkString("\n")
    assert(fileContent.contains(s"<name>$workspaceFolderName</name>"))
    assert(fileContent.contains("<name>org.eclipse.jdt.core.javabuilder</name>"))
    assert(fileContent.contains("<nature>org.eclipse.jdt.core.javanature</nature>"))

    if (linkedResourceNames.nonEmpty) {
      assert(fileContent.contains("<link>"))
      for (linkedResourceName <- linkedResourceNames) {
        assert(fileContent.contains(s"<name>$linkedResourceName</name>"))
        assert(fileContent.contains("<type>2</type>"))
        assert(fileContent.contains(s"$linkedResourceName</location>"))
      }
    } else {
      assert(!fileContent.contains("<link>"))
    }
  }

  /**
   *  Checks for the standard ".classpath" file and its content generated correctly. As this
   *  contains the main Java project configuration, this tests the main bulk of configuration.
   *
   *  All the (test) source directories, dependent projects and roughly tests libraries.
   */
  def checkClasspathFile(
      projectPath: os.Path,
      srcFolderName: String | Null,
      linkedResourceNames: Seq[String],
      testSrcFolderNames: Seq[String],
      dependentProjectNames: Seq[String],
      libraryNames: Seq[String]
  ): Unit = {
    val generatedFile = projectPath / ".classpath"
    assert(os.exists(generatedFile))

    val javaVersion = getJavaVersion
    val fileContent = os.read.lines(generatedFile).mkString("\n")
    assert(fileContent.contains("<classpathentry kind=\"output\" path=\"bin\"/>"))
    assert(fileContent.contains(
      s"<classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-$javaVersion\"/>"
    ))

    // Source folder classpath entry only created when there are actually sources in it
    if (srcFolderName != null) {
      val adjustedSrcFolderName = srcFolderName.replace("/", "-").replace("\\", "-")
      assert(fileContent.contains(
        s"<classpathentry kind=\"src\" path=\"$srcFolderName\" output=\"bin/$adjustedSrcFolderName-classes\"/>"
      ))
    }

    // Check for linked resources as additional source folders, e.g. generated sources
    for (linkedResourceName <- linkedResourceNames) {
      assert(fileContent.contains(
        s"<classpathentry kind=\"src\" path=\"$linkedResourceName\" output=\"bin/$linkedResourceName-classes\"/>"
      ))
    }

    // Check for test module source folders
    for (testSrcFolderName <- testSrcFolderNames) {
      val adjustedTestSrcFolderName = testSrcFolderName.replace("/", "-").replace("\\", "-")
      assert(fileContent.contains(s"<classpathentry kind=\"src\" path=\"$testSrcFolderName\" output=\"bin/$adjustedTestSrcFolderName-classes\""))
    }
    assert(getOccurrences(
      fileContent,
      "<attribute name=\"test\" value=\"true\"/>"
    ) == testSrcFolderNames.size)

    // Check for dependent projects
    for (dependentProjectName <- dependentProjectNames) {
      assert(fileContent.contains(
        s"<classpathentry kind=\"src\" combineaccessrules=\"false\" path=\"/$dependentProjectName\"/>"
      ))
    }
    assert(getOccurrences(fileContent, "combineaccessrules") == dependentProjectNames.size)

    // Check for dependent libraries - this is a vague check only but this is fine ^^
    for (libraryName <- libraryNames) {
      assert(fileContent.contains(s"$libraryName-"))
    }
  }
}
