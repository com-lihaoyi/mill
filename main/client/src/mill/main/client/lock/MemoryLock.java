package mill.main.client.lock;

import java.util.concurrent.locks.ReentrantLock;

class MemoryLock extends Lock {

    private ReentrantLock innerLock = new ReentrantLock();

    public boolean probe() {
        return !innerLock.isLocked();
    }

    public Locked lock() {
        innerLock.lock();
        return new MemoryLocked(innerLock);
    }

    public Locked tryLock() {
        if (innerLock.tryLock()) return new MemoryLocked(innerLock);
        else return null;
    }

    @Override
    public void close() throws Exception {
        innerLock.unlock();
    }
}
