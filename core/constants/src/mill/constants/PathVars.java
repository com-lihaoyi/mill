package mill.constants;

/**
 * Central place containing all the path variables that Mill uses in <code>PathRef</code> or <code>os.Path</code>.
 */
public interface PathVars {

  /**
   * Output directory where Mill workers' state and Mill tasks output should be
   * written to
   */
  String MILL_OUT = "MILL_OUT";

  /**
   * The Mill project workspace root directory.
   */
  String WORKSPACE = "WORKSPACE";

  String HOME = "HOME";

  String ROOT = "ROOT";
}
