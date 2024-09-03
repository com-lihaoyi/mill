package mill.main.client;


public class CodeGenConstants {
    /**
     * Global package prefix for Mill builds. Cannot be `build` because
     * it would conflict with the name of the `lazy val build ` object
     * we import to work around the need for the `.package` suffix, so
     * we add an `_` and call it `build_`
     */
    final public static String globalPackagePrefix = "build_";

    /**
     * What the wrapper objects are called. Not `package` because we don't
     * want them to be `package objects` due to weird edge case behavior,
     * even though we want them to look like package objects to users and IDEs,
     * so we add an `_` and call it `package_`
     */
    final public static String wrapperObjectName = "package_";

    /**
     * The name of the root build file
     */
    final public static String[] rootBuildFileNames = {"build.mill", "build.sc"};

    /**
     * The name of any sub-folder build files
     */
    final public static String[] nestedBuildFileNames = {"package.mill", "package.sc"};

    /**
     * The extensions used by build files
     */
    final public static String[] buildFileExtensions = {"mill", "sc"};

    /**
     * The user-facing name for the root of the module tree.
     */
    final public static String rootModuleAlias = "build";
}
