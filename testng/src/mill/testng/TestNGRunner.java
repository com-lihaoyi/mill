package mill.testng;

import org.scalatools.testing.Fingerprint;
import org.scalatools.testing.Logger;
import org.scalatools.testing.Runner2;
import org.scalatools.testing.EventHandler;

public class TestNGRunner extends Runner2 {
    ClassLoader testClassLoader;
    Logger[] loggers;
    TestRunState state;
    public TestNGRunner(ClassLoader testClassLoader, Logger[] loggers, TestRunState state) {
        this.testClassLoader = testClassLoader;
        this.loggers = loggers;
        this.state = state;
    }
    public void run(String testClassname, Fingerprint fingerprint, EventHandler eventHandler, String[] testOptions) {
        if (TestRunState.permissionToExecute.tryAcquire()) {
            TestNGInstance.start(
                    TestNGInstance.loggingTo(loggers)
                    .loadingClassesFrom(testClassLoader)
                    .using(testOptions)
                    .storingEventsIn(state.recorder)
            );

            state.testCompletion.countDown();
        }

        try{
            state.testCompletion.await();
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }

        state.recorder.replayTo(eventHandler, testClassname, loggers);
    }
}
