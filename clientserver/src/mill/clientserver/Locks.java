package mill.clientserver;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReentrantLock;


abstract class Lock{
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
interface Locked{
    void release() throws Exception;
}
class Locks{
    public Lock processLock;
    public Lock serverLock;
    public Lock clientLock;
    static Locks files(String lockBase) throws Exception{
        return new Locks(){{
            processLock = new FileLock(lockBase + "/pid");

            serverLock = new FileLock(lockBase + "/serverLock");

            clientLock = new FileLock(lockBase + "/clientLock");
        }};
    }
    static Locks memory(){
        return new Locks(){{
            this.processLock = new MemoryLock();
            this.serverLock = new MemoryLock();
            this.clientLock = new MemoryLock();
        }};
    }
}
class FileLocked implements Locked{
    private java.nio.channels.FileLock lock;
    public FileLocked(java.nio.channels.FileLock lock){
        this.lock = lock;
    }
    public void release() throws Exception{
        this.lock.release();
    }
}

class FileLock extends Lock{
    String path;
    RandomAccessFile raf;
    FileChannel chan;
    public FileLock(String path) throws Exception{
        this.path = path;
        raf = new RandomAccessFile(path, "rw");
        chan = raf.getChannel();
    }

    public Locked lock() throws Exception{
        return new FileLocked(chan.lock());
    }
    public Locked tryLock() throws Exception{
        java.nio.channels.FileLock l = chan.tryLock();
        if (l == null) return null;
        else return new FileLocked(l);
    }
    public boolean probe()throws Exception{
        java.nio.channels.FileLock l = chan.tryLock();
        if (l == null) return false;
        else {
            l.release();
            return true;
        }
    }
}
class MemoryLocked implements Locked{
    java.util.concurrent.locks.Lock l;
    public MemoryLocked(java.util.concurrent.locks.Lock l){
        this.l = l;
    }
    public void release() throws Exception{
        l.unlock();
    }
}

class MemoryLock extends Lock{
    ReentrantLock innerLock = new ReentrantLock(true);

    public boolean probe(){
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
}
