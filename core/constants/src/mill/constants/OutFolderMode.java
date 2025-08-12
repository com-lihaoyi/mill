package mill.constants;

public enum OutFolderMode {
  /** For regular invocations, uses {@link OutFiles#out}. */
  REGULAR("regular"),

  /** For BSP invocations, uses {@link OutFiles#bspOut}. */
  BSP("bsp");

  public final String asString;

  OutFolderMode(String asString) {
    this.asString = asString;
  }
}
