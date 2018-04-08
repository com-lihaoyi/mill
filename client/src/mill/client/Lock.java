package mill.client;
public abstract class Lock{
    abstract public Locked lock() throws Exception;
    abstract public Locked tryLock() throws Exception;
    public void await() throws Exception{
        lock().release();
    }

    /**
     * Returns `true` if the lock is *available for taking*
     */
    abstract public boolean probe() throws Exception;
}