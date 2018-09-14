package mill.testng;

import com.beust.jcommander.JCommander;
import org.testng.CommandLineArgs;
import sbt.testing.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class TestNGTask implements Task {

    TaskDef taskDef;
    TestNGRunner runner;
    CommandLineArgs cliArgs;
    public TestNGTask(TaskDef taskDef,
                      TestNGRunner runner,
                      CommandLineArgs cliArgs){
        this.taskDef = taskDef;
        this.runner = runner;
        this.cliArgs = cliArgs;
    }

    @Override
    public String[] tags() {
        return new String[0];
    }

    @Override
    public Task[] execute(EventHandler eventHandler, Logger[] loggers) {
        new TestNGInstance(
                loggers,
                runner.testClassLoader,
                cliArgs,
                eventHandler
        ).run();
        return new Task[0];
    }

    @Override
    public TaskDef taskDef() {
        return taskDef;
    }
}

public class TestNGRunner implements Runner {
    ClassLoader testClassLoader;
    String[] args;
    String[] remoteArgs;
    public TestNGRunner(String[] args, String[] remoteArgs, ClassLoader testClassLoader) {
        this.testClassLoader = testClassLoader;
        this.args = args;
        this.remoteArgs = remoteArgs;
    }

    public Task[] tasks(TaskDef[] taskDefs) {
        CommandLineArgs cliArgs = new CommandLineArgs();
        new JCommander(cliArgs, args); // args is an output parameter of the constructor!
        if(cliArgs.testClass == null){
            String[] names = new String[taskDefs.length];
            for(int i = 0; i < taskDefs.length; i += 1){
                names[i] = taskDefs[i].fullyQualifiedName();
            }
            cliArgs.testClass = String.join(",", names);
        }
        if (taskDefs.length == 0) return new Task[]{};
        else return new Task[]{new TestNGTask(taskDefs[0], this, cliArgs)};
    }

    public String done() { return null; }

    public String[] remoteArgs() { return remoteArgs; }

    public String[] args() { return args; }
}
