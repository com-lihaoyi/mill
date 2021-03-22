package mill.main.client;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReentrantLock;

public class Locks implements AutoCloseable {

    public Lock processLock;
    public Lock serverLock;
    public Lock clientLock;

    public static Locks files(String lockBase) throws Exception {
        return new Locks(){{
            processLock = new FileLock(lockBase + "/pid");
            serverLock = new FileLock(lockBase + "/serverLock");
            clientLock = new FileLock(lockBase + "/clientLock");
        }};
    }

    public static Locks memory() {
        return new Locks(){{
            this.processLock = new MemoryLock();
            this.serverLock = new MemoryLock();
            this.clientLock = new MemoryLock();
        }};
    }

    @Override
    public void close() throws Exception {
        processLock.close();
        serverLock.close();
        clientLock.close();
    }
}

class FileLocked implements Locked {

    private java.nio.channels.FileLock lock;

    public FileLocked(java.nio.channels.FileLock lock) {
        this.lock = lock;
    }

    public void release() throws Exception {
        this.lock.release();
    }
}

class FileLock extends Lock {

    private RandomAccessFile raf;
    private FileChannel chan;

    public FileLock(String path) throws Exception {
        raf = new RandomAccessFile(path, "rw");
        chan = raf.getChannel();
    }

    public Locked lock() throws Exception {
        return new FileLocked(chan.lock());
    }

    public Locked tryLock() throws Exception {
        java.nio.channels.FileLock l = chan.tryLock();
        if (l == null) return null;
        else return new FileLocked(l);
    }

    public boolean probe() throws Exception {
        java.nio.channels.FileLock l = chan.tryLock();
        if (l == null) return false;
        else {
            l.release();
            return true;
        }
    }

    @Override
    public void close() throws Exception {
        chan.close();
        raf.close();
    }
}

class MemoryLocked implements Locked {

    private java.util.concurrent.locks.Lock l;

    public MemoryLocked(java.util.concurrent.locks.Lock l) {
        this.l = l;
    }

    public void release() throws Exception {
        l.unlock();
    }
}

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
