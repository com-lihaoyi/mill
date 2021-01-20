package mill.testng;


import java.util.Arrays;

import org.testng.CommandLineArgs;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import sbt.testing.EventHandler;
import sbt.testing.Logger;

class TestNGListener implements ITestListener{

    private final EventHandler basket;
    private final boolean printEnabled;

    private String lastName = "";

    public TestNGListener(EventHandler basket){
        this.basket = basket;
        String prop = System.getProperty("mill.testng.printProgress", "1");
        this.printEnabled = Arrays.asList("1", "y", "yes", "true").contains(prop);
    }

    public void onTestStart(ITestResult iTestResult) {
        String newName = iTestResult.getTestClass().getName() + " " + iTestResult.getName() + " ";
        if(!newName.equals(lastName)){
            if (!lastName.equals("")){
                System.out.println();
            }
            lastName = newName;
            System.out.print(lastName);
        }
    }

    public void onTestSuccess(ITestResult iTestResult) {
        printProgress('+');
        basket.handle(ResultEvent.success(iTestResult));
    }

    public void onTestFailure(ITestResult iTestResult) {
        printProgress('X');
        basket.handle(ResultEvent.failure(iTestResult));
    }

    public void onTestSkipped(ITestResult iTestResult) {
        printProgress('-');
        basket.handle(ResultEvent.skipped(iTestResult));
    }

    public void onTestFailedButWithinSuccessPercentage(ITestResult iTestResult) {
        basket.handle(ResultEvent.failure(iTestResult));
    }

    public void onStart(ITestContext iTestContext) {}

    public void onFinish(ITestContext iTestContext) {}
    
    protected void printProgress(char progress) {
        if(printEnabled) {
            System.out.print(progress);
        }
    }
}

public class TestNGInstance extends TestNG{
    public TestNGInstance(Logger[] loggers,
                          ClassLoader testClassLoader,
                          CommandLineArgs args,
                          EventHandler eventHandler) {
        addClassLoader(testClassLoader);

        this.addListener(new TestNGListener(eventHandler));

        configure(args);
    }
}


