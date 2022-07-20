package mill.testng;

import com.beust.jcommander.JCommander;
import org.testng.CommandLineArgs;
import sbt.testing.EventHandler;
import sbt.testing.Logger;
import sbt.testing.Runner;
import sbt.testing.Task;
import sbt.testing.TaskDef;

class TestNGTask implements Task {

    private final TaskDef taskDef;
    private final ClassLoader testClassLoader;
    private final CommandLineArgs cliArgs;

    public TestNGTask(TaskDef taskDef,
                      ClassLoader testClassLoader,
                      CommandLineArgs cliArgs){
        this.taskDef = taskDef;
        this.testClassLoader = testClassLoader;
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
                testClassLoader,
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

    private final ClassLoader testClassLoader;
    private final String[] args;
    private final String[] remoteArgs;

    public TestNGRunner(String[] args, String[] remoteArgs, ClassLoader testClassLoader) {
        this.testClassLoader = testClassLoader;
        this.args = args;
        this.remoteArgs = remoteArgs;
    }

    public Task[] tasks(TaskDef[] taskDefs) {
        CommandLineArgs cliArgs = new CommandLineArgs();
        new JCommander(cliArgs).parse(args); // args is an output parameter of the constructor!
        if(cliArgs.testClass == null){
            String[] names = new String[taskDefs.length];
            for(int i = 0; i < taskDefs.length; i += 1){
                names[i] = taskDefs[i].fullyQualifiedName();
            }
            cliArgs.testClass = String.join(",", names);
        }
        if (taskDefs.length == 0) return new Task[]{};
        else return new Task[]{new TestNGTask(taskDefs[0], testClassLoader, cliArgs)};
    }

    public String done() { return null; }

    public String[] remoteArgs() { return remoteArgs; }

    public String[] args() { return args; }
}
