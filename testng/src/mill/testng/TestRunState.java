package mill.testng;

import java.util.concurrent.Semaphore;
import java.util.concurrent.CountDownLatch;

public class TestRunState {
    public static Semaphore permissionToExecute = new Semaphore(1);
    public static CountDownLatch testCompletion = new CountDownLatch(1);
    public static EventRecorder recorder = new EventRecorder();
}