package jointcompile;

import jointcompile.GroovyGreeter;


public class JavaMain {

  public static void main(String[] args) {
    var greeter = new GroovyGreeter("JointCompile");
    greeter.greet();
  }
}
