
package mill.testng;

import org.scalatools.testing.Event;
import org.scalatools.testing.Result;
import org.testng.ITestResult;

public class ResultEvent implements Event {
    public Result result;
    public String testName;
    public String description;
    public Throwable error;

    public ResultEvent(Result result, String testName, String description, Throwable error) {
        this.result = result;
        this.testName = testName;
        this.description = description;
        this.error = error;
    }


    public Result result(){ return result; }
    public String testName(){ return testName; }
    public String description(){ return description; }
    public Throwable error(){ return error; }

    static ResultEvent failure(ITestResult result){ return event(Result.Failure, result); }
    static ResultEvent skipped(ITestResult result){ return event(Result.Skipped, result); }
    static ResultEvent success(ITestResult result){ return event(Result.Success, result); }

    static ResultEvent event(Result result, ITestResult testNGResult) {
        return new ResultEvent(
                result,
                testNGResult.getName(),
                testNGResult.getName(),
                result != Result.Success ? testNGResult.getThrowable() : null
        );
    }
    static String classNameOf(ITestResult result){ return result.getTestClass().getName(); }
}