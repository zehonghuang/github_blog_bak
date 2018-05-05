---
title: 【Java并发】JUC-lock包AbstractQueuedSynchronizer的源码分析（二）
date: 2018-03-11 13:17:20
tags:
  - java　
  - 并发编程
  - 同步
  - 公平锁
  - 非公平锁
  - 可重入锁
  - 读写锁
  - 共享锁
  - 独占锁
  - ReentrantReadWriteLock
categories:
  - java并发编程
---

 sss

---

``` java
public class ReentrantReadWriteLock implements ReadWriteLock, java.io.Serializable {
  private final ReentrantReadWriteLock.ReadLock readerLock;
  private final ReentrantReadWriteLock.WriteLock writerLock;
  final Sync sync;
  public ReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
  public ReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }
}
```

``` java
abstract static class Sync extends AbstractQueuedSynchronizer {
  static final int SHARED_SHIFT   = 16;
  static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
  static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
  static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;
  static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
  static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

  private transient ThreadLocalHoldCounter readHolds;
  private transient HoldCounter cachedHoldCounter;
  private transient Thread firstReader;
  private transient int firstReaderHoldCount;
}
```
sss
``` java
protected final int tryAcquireShared(int unused) {
  Thread current = Thread.currentThread();
  int c = getState();
  if (exclusiveCount(c) != 0 &&
    getExclusiveOwnerThread() != current)
    return -1;
  int r = sharedCount(c);
  if (!readerShouldBlock() &&
    r < MAX_COUNT &&
    compareAndSetState(c, c + SHARED_UNIT)) {
    if (r == 0) {
      firstReader = current;
      firstReaderHoldCount = 1;
    } else if (firstReader == current) {
      firstReaderHoldCount++;
    } else {
      HoldCounter rh = cachedHoldCounter;
      if (rh == null || rh.tid != getThreadId(current))
        cachedHoldCounter = rh = readHolds.get();
      else if (rh.count == 0)
        readHolds.set(rh);
      rh.count++;
    }
    return 1;
  }
  return fullTryAcquireShared(current);
}
```

ddd
``` java
final int fullTryAcquireShared(Thread current) {
  /*
   * This code is in part redundant with that in
   * tryAcquireShared but is simpler overall by not
   * complicating tryAcquireShared with interactions between
   * retries and lazily reading hold counts.
   */
  HoldCounter rh = null;
  for (;;) {
    int c = getState();
    if (exclusiveCount(c) != 0) {
      if (getExclusiveOwnerThread() != current)
        return -1;
      // else we hold the exclusive lock; blocking here
      // would cause deadlock.
    } else if (readerShouldBlock()) {
      // Make sure we're not acquiring read lock reentrantly
      if (firstReader == current) {
          // assert firstReaderHoldCount > 0;
      } else {
        if (rh == null) {
          rh = cachedHoldCounter;
          if (rh == null || rh.tid != getThreadId(current)) {
            rh = readHolds.get();
            if (rh.count == 0)
              readHolds.remove();
          }
        }
        if (rh.count == 0)
          return -1;
      }
    }
    if (sharedCount(c) == MAX_COUNT)
      throw new Error("Maximum lock count exceeded");
    if (compareAndSetState(c, c + SHARED_UNIT)) {
      if (sharedCount(c) == 0) {
        firstReader = current;
        firstReaderHoldCount = 1;
      } else if (firstReader == current) {
        firstReaderHoldCount++;
      } else {
        if (rh == null)
          rh = cachedHoldCounter;
        if (rh == null || rh.tid != getThreadId(current))
          rh = readHolds.get();
        else if (rh.count == 0)
          readHolds.set(rh);
        rh.count++;
        cachedHoldCounter = rh; // cache for release
      }
      return 1;
    }
  }
}
```

sss
``` java
static final class NonfairSync extends Sync {
  private static final long serialVersionUID = -8159625535654395037L;
  final boolean writerShouldBlock() {
    return false;
  }
  final boolean readerShouldBlock() {
    return apparentlyFirstQueuedIsExclusive();
  }
}
static final class FairSync extends Sync {
  private static final long serialVersionUID = -2274990926593161451L;
  final boolean writerShouldBlock() {
    return hasQueuedPredecessors();
  }
  final boolean readerShouldBlock() {
    return hasQueuedPredecessors();
  }
}
```
