package mill.testng;



import sbt.testing.*;


public class TestNGFramework implements Framework {
    public String name(){ return "TestNG"; }

    public Fingerprint[] fingerprints() {
        return new Fingerprint[]{Annotated.instance};
    }

    @Override
    public Runner runner(String[] args, String[] remoteArgs, ClassLoader classLoader) {
        return new TestNGRunner(args, remoteArgs, classLoader);
    }
}

class Annotated implements AnnotatedFingerprint{
    final public static Annotated instance = new Annotated("org.testng.annotations.Test");
    String annotationName;
    public Annotated(String annotationName) {
        this.annotationName = annotationName;
    }
    public String annotationName(){return annotationName;}
    public boolean isModule(){return false;}
}
