package mill.testng;

import sbt.testing.*;

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
        if (TestRunState.permissionToExecute.tryAcquire()) {
            TestNGInstance.start(
                    TestNGInstance.loggingTo(loggers)
                            .loadingClassesFrom(runner.testClassLoader)
                            .using(runner.args())
                            .storingEventsIn(runner.state.recorder)
            );

            runner.state.testCompletion.countDown();
        }

        try{
            runner.state.testCompletion.await();
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }

        runner.state.recorder.replayTo(eventHandler, taskDef.fullyQualifiedName(), loggers);
        return new Task[0];
    }

    @Override
    public TaskDef taskDef() {
        return taskDef;
    }
}
public class TestNGRunner implements Runner {
    ClassLoader testClassLoader;
    TestRunState state;
    String[] args;
    String[] remoteArgs;
    public TestNGRunner(String[] args, String[] remoteArgs, ClassLoader testClassLoader, TestRunState state) {
        this.testClassLoader = testClassLoader;
        this.state = state;
        this.args = args;
        this.remoteArgs = remoteArgs;
    }

    public Task[] tasks(TaskDef[] taskDefs) {
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
