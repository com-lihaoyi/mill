package mill.testng;

import org.scalatools.testing.Event;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;

import org.scalatools.testing.EventHandler;
import org.scalatools.testing.Logger;

import java.util.HashMap;

public class EventRecorder extends TestListenerAdapter {
    private HashMap<String, java.util.List<Event>> basket = new HashMap<>();

    String initKey(ITestResult result){
        String key = ResultEvent.classNameOf(result);
        if (!basket.containsKey(key)) basket.put(key, new java.util.ArrayList<Event>());
        return key;
    }
    public void onTestFailure(ITestResult result){
        String key = initKey(result);
        basket.get(key).add(ResultEvent.failure(result));
    }
    public void onTestSkipped(ITestResult result){
        String key = initKey(result);
        basket.get(key).add(ResultEvent.skipped(result));
    }
    public void onTestSuccess(ITestResult result){
        String key = initKey(result);
        basket.get(key).add(ResultEvent.success(result));
    }

    void replayTo(EventHandler sbt, String className, Logger[] loggers){
        synchronized (basket){
            for(Event e: basket.remove(className)){
                sbt.handle(e);
            }
        }
    }
}
