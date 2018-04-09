package mill.testng;


import sbt.testing.Logger;

import org.testng.CommandLineArgs;
import org.testng.TestNG;

import com.beust.jcommander.JCommander;

public class TestNGInstance {
    Logger[] loggers;
    ConfigurableTestNG configurableTestNG = new ConfigurableTestNG();
    public TestNGInstance(Logger[] loggers){
        this.loggers = loggers;
    }
    TestNGInstance loadingClassesFrom(ClassLoader testClassLoader){
        configurableTestNG.addClassLoader(testClassLoader);
        return TestNGInstance.this;
    }
    TestNGInstance using(String[] testOptions){
        CommandLineArgs args = new CommandLineArgs();
        new JCommander(args, testOptions); // args is an output parameter of the constructor!
        configurableTestNG.configure(args);
        return TestNGInstance.this;
    }

    TestNGInstance storingEventsIn(EventRecorder basket){
        configurableTestNG.addListener(basket);
        return TestNGInstance.this;
    }

    static void start(TestNGInstance testNG){
        testNG.configurableTestNG.run();
    }
    static TestNGInstance loggingTo(Logger[] loggers){ return new TestNGInstance(loggers); }
}


class ConfigurableTestNG extends TestNG{ // the TestNG method we need is protected
    public void configure(CommandLineArgs args) { super.configure(args); }
}
