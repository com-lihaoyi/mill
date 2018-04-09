package mill.testng;



import org.scalatools.testing.*;


public class TestNGFramework implements Framework {
    public String name(){ return "TestNG"; }
    public Fingerprint[] tests(){
        return new Fingerprint[]{
                new Annotated("org.testng.annotations.Test")
        };
    }

    public Runner testRunner(ClassLoader testClassLoader, Logger[] loggers){
        return new TestNGRunner(testClassLoader, loggers, sharedState);
    }

    private TestRunState sharedState = new TestRunState();
}

class Annotated implements AnnotatedFingerprint{
    String annotationName;
    public Annotated(String annotationName) {
        this.annotationName = annotationName;
    }
    public String annotationName(){return annotationName;}
    public boolean isModule(){return false;}
}