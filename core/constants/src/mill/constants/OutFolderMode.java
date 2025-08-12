package mill.constants;

public enum OutFolderMode {
  /** For regular invocations, uses {@link OutFiles#out}. */
  REGULAR,

  /** For BSP invocations, uses {@link OutFiles#bspOut}. */
  BSP;

  public String asString() {
    return name().toLowerCase();
  }

  public static OutFolderMode fromString(String s) {
    return valueOf(s.toUpperCase());
  }
}
