
package mill.testng;

import sbt.testing.*;
import org.testng.ITestResult;

public class ResultEvent {
    static Event failure(ITestResult result){ return event(Status.Failure, result); }
    static Event skipped(ITestResult result){ return event(Status.Skipped, result); }
    static Event success(ITestResult result){ return event(Status.Success, result); }

    static Event event(Status result, ITestResult testNGResult) {
        return new Event() {
            public String fullyQualifiedName() {
                return testNGResult.getTestClass().getName();
            }

            public Fingerprint fingerprint() {
                return TestNGFingerprint.instance;
            }

            public Selector selector() {
                return new SuiteSelector();
            }

            public Status status() {
                return result;
            }

            public OptionalThrowable throwable() {
                if (result != Status.Success){
                    return new OptionalThrowable(testNGResult.getThrowable());
                }else {
                    return new OptionalThrowable();
                }
            }

            @Override
            public long duration() {
                return testNGResult.getEndMillis() - testNGResult.getStartMillis();
            }
        };
    }
    static String classNameOf(ITestResult result){ return result.getTestClass().getName(); }
}