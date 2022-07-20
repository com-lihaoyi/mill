package mill.main.client.lock;

public abstract class Lock implements AutoCloseable {

    public abstract Locked lock() throws Exception;

    public abstract Locked tryLock() throws Exception;

    public void await() throws Exception {
        lock().release();
    }

    /**
     * Returns `true` if the lock is *available for taking*
     */
    public abstract boolean probe() throws Exception;
}
