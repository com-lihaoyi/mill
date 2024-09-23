package mill.main.client.lock;

import java.util.concurrent.locks.ReentrantLock;

class DummyLock extends Lock {

    public boolean probe() {
        return true;
    }

    public Locked lock() {
        return new DummyTryLocked();
    }

    public TryLocked tryLock() {
        return new DummyTryLocked();
    }

    @Override
    public void close() throws Exception {
    }
}
