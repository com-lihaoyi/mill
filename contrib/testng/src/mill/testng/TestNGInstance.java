package mill.testng;


import org.testng.*;
import sbt.testing.EventHandler;
import sbt.testing.Logger;

import com.beust.jcommander.JCommander;

import java.net.URLClassLoader;
import java.util.Arrays;

class TestNGListener implements ITestListener{
    EventHandler basket;
    String lastName = "";
    public TestNGListener(EventHandler basket){
        this.basket = basket;
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
        System.out.print('+');
        basket.handle(ResultEvent.success(iTestResult));
    }

    public void onTestFailure(ITestResult iTestResult) {
        System.out.print('X');
        basket.handle(ResultEvent.failure(iTestResult));
    }

    public void onTestSkipped(ITestResult iTestResult) {
        System.out.print('-');
        basket.handle(ResultEvent.skipped(iTestResult));
    }

    public void onTestFailedButWithinSuccessPercentage(ITestResult iTestResult) {
        basket.handle(ResultEvent.failure(iTestResult));
    }

    public void onStart(ITestContext iTestContext) {}

    public void onFinish(ITestContext iTestContext) {}
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


