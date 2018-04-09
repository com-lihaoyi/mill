package mill.testng;

import sbt.testing.*;

import java.util.Arrays;
import java.util.Collections;

class TestNGTask implements Task {

    TaskDef taskDef;
    TestNGRunner runner;
    public TestNGTask(TaskDef taskDef, TestNGRunner runner){
        this.taskDef = taskDef;
        this.runner = runner;
    }

    @Override
    public String[] tags() {
        return new String[0];
    }

    @Override
    public Task[] execute(EventHandler eventHandler, Logger[] loggers) {
        System.out.println("Executing " + taskDef.fullyQualifiedName());
        new TestNGInstance(
                loggers,
                runner.testClassLoader,
                runner.args(),
                taskDef.fullyQualifiedName(),
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
        System.out.println("TestNGRunner#tasks " + Arrays.toString(taskDefs));
        Task[] out = new Task[taskDefs.length];
        for (int i = 0; i < taskDefs.length; i += 1) {
            out[i] = new TestNGTask(taskDefs[i], this);
        }
        return out;
    }

    public String done() { return null; }

    public String[] remoteArgs() { return remoteArgs; }

    public String[] args() { return args; }
}
