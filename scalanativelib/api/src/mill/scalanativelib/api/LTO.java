package mill.scalanativelib.api;
public enum LTO{
    /** LTO disabled */
  None("none"),
    /** LTO mode uses ThinLTO */
  Thin("thin"),
    /** LTO mode uses standard LTO compilation */
  Full("full");

  public String value;
  LTO(String value0){
      value = value0;
  }
}
