package mill.scalanativelib.api;
public enum ReleaseMode{
    /** Fast compile, little optimization. */
  Debug("debug"),
    /** Runtime optimize, faster compile, smaller binary. */
  ReleaseFast("release-fast"),
    /** Runtime optimize, prefer speed over compile time and size. */
  ReleaseFull("release-full");

  public String value;
  ReleaseMode(String value0){
      value = value0;
  }
}
