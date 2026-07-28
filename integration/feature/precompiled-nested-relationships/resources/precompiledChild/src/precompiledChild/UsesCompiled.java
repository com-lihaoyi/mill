package precompiledChild;

import compiledChild.Hello;

public class UsesCompiled {
  public static String show() { return Hello.greeting(); }
}
