package mill.constants;

import java.util.List;

public class CodeGenConstants {
  /**
   * Global package prefix for Mill builds. Cannot be `build` because
   * it would conflict with the name of the `lazy val build ` object
   * we import to work around the need for the `.package` suffix, so
   * we add an `_` and call it `build_`
   */
  public static final String globalPackagePrefix = "build";

  /**
   * What the wrapper objects are called. Not `package` because we don't
   * want them to be `package objects` due to weird edge case behavior,
   * even though we want them to look like package objects to users and IDEs,
   * so we add an `_` and call it `package_`
   */
  public static final String wrapperObjectName = "package_";

  /**
   * The name of the root build file
   */
  public static final List<String> rootBuildFileNames = List.of("build.mill.yaml", "build.mill");

  /**
   * The name of any sub-folder build files
   */
  public static final List<String> nestedBuildFileNames =
      List.of("package.mill.yaml", "package.mill");

  /**
   * The extensions used by build files
   */
  public static final List<String> buildFileExtensions = List.of("mill.yaml", "mill");

  /**
   * The user-facing name for the root of the module tree.
   */
  public static final String rootModuleAlias = "build";
}
