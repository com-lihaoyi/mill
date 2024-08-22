package mill.main.client.lock;

class MemoryLocked implements Locked {

    final protected java.util.concurrent.locks.Lock lock;

    public MemoryLocked(java.util.concurrent.locks.Lock lock) {
        this.lock = lock;
    }

    public void release() throws Exception {
        lock.unlock();
    }
}
