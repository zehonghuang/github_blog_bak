---
title: 【Java并发】JUC.lock包AbstractQueuedSynchronizer的源码分析（一）
date: 2018-02-27 20:28:55
tags:
  - java　
  - 并发编程
  - 同步
  - 公平锁
  - 非公平锁
  - 可重入锁
  - ReentrantLock
categories:
    - java并发编程
---

平时开发不少使用`synchronized`，java提供了这个语法特性使用起来非常方便，但灵活性似乎不太好，因为它只是个独占且可重入锁，无法唤醒指定线程和实现共享等其他类型锁。

jdk提供了JUC这个并发包，里面就包含了功能更多的lock包，有ReentrantLock、ReentrantReadWriteLock等多功能锁，而实现这些锁的核心在于一个同步器AbstractQueuedSynchronizer（AQS）。

我们从AQS开始分析，了解一下jdk提供的公平锁、非公平锁、共享锁、独占锁、读写锁分别是怎么实现的。

---

咱先来看下ReentrantLock怎么用的，语义上跟synchronized是一致的，不过lock()与unlock()需要成对存在。
``` java
private int count = 0;
public void task() {
  Lock lock = new ReentrantLock();
  lock.lock();
  for(int j = 0; j < 10; j++) {
    count++;
  }
  lock.unlock();
}
```
这里看到lock()是调用了抽象内部类Sync的lock方法，具体实现在其子类FairSync、NonfairSync。
``` java
public class ReentrantLock implements Lock, java.io.Serializable {
  private final Sync sync;
  public void lock() {
    sync.lock();
  }
  abstract static class Sync extends AbstractQueuedSynchronizer {
    abstract void lock();
  }
}
```

我们先来看一下ReentrantLock的公平锁怎么实现的，可以看到lock()只调用了acquire(1)，acquire是AQS的一个方法。
``` java
static final class FairSync extends Sync {
  private static final long serialVersionUID = -3000897897090466540L;
  final void lock() {
      acquire(1);
  }
  protected final boolean tryAcquire(int acquires) {
    //获取当前线程
    final Thread current = Thread.currentThread();
    int c = getState();
    //如果c=0，则锁处于空闲状态
    if (c == 0) {
      //如果当前线程没有前节点的话，将cas state，成功则唤醒当前线程，返回true
      if (!hasQueuedPredecessors() &&
            compareAndSetState(0, acquires)) {
        setExclusiveOwnerThread(current);
        return true;
      }
    }
    //c非0，如果当前线程已经持有锁，则state + 1，返回true。再次已实现可重入性
    else if (current == getExclusiveOwnerThread()) {
      int nextc = c + acquires;
      if (nextc < 0)
        throw new Error("Maximum lock count exceeded");
        setState(nextc);
        return true;
    }
    return false;
  }
}
```

先看一下AQS的acquire方法，每次调用都会去尝试获取锁，tryAcquire方法是个空方法，有子类做具体的实现。回头来看FairSync的实现。

`current`是当前线程，先判断锁是非空闲；如果是，hasQueuedPredecessors再判断线程是否有前节点，没有则compareAndSetState将state设置为acquires。

如果锁不是空闲状态，且当前线程是锁的持有人，则将state设置为state + acquires。在这一步已经实现了Lock的可重入性，线程进入lock()后，会判断当前线程是否已经持有锁，是的话将state加1.
``` java
//记录lock()被多少调用了几次，0表示空闲状态，大于0表示忙碌状态
private volatile int state;
public final void acquire(int arg) {
  //尝试获取锁，若失败，则进入等待队列;
  //acquireQueued将线程挂起，挂起异常则selfInterrupt中断当前线程
  if (!tryAcquire(arg) &&
          acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
      selfInterrupt();
}
//尝试获取锁
protected boolean tryAcquire(int arg) {
  throw new UnsupportedOperationException();
}
protected boolean tryRelease(int arg) {
  throw new UnsupportedOperationException();
}
```

![双链表](http://p4ygo03xz.bkt.clouddn.com/github-blog/image/AbstractQueuedSynchronizer-Node.png)

``` java
private transient volatile Node head;
private transient volatile Node tail;

static final class Node {
  //共享类型
  static final Node SHARED = new Node();
  //独占类型
  static final Node EXCLUSIVE = null;

  //取消状态
  static final int CANCELLED =  1;
  //等待触发
  static final int SIGNAL    = -1;
  //等待条件符合
  static final int CONDITION = -2;
  //节点状态向后传递
  static final int PROPAGATE = -3;

  //挂起的状态
  volatile int waitStatus;

  volatile Node prev;

  volatile Node next;

  volatile Thread thread;

  Node nextWaiter;

  final boolean isShared() {
      return nextWaiter == SHARED;
  }
  final Node predecessor() throws NullPointerException {
    Node p = prev;
    if (p == null)
        throw new NullPointerException();
    else
        return p;
  }
  Node() {    // Used to establish initial head or SHARED marker
  }
  Node(Thread thread, Node mode) {     // Used by addWaiter
    this.nextWaiter = mode;
    this.thread = thread;
  }
  Node(Thread thread, int waitStatus) { // Used by Condition
    this.waitStatus = waitStatus;
    this.thread = thread;
  }
}

```

sss

``` java

private Node addWaiter(Node mode) {
  //创建当前线程为新节点
  Node node = new Node(Thread.currentThread(), mode);
  // Try the fast path of enq; backup to full enq on failure
  //
  Node pred = tail;
  if (pred != null) {
    node.prev = pred;
    //如果此时tail没有被其他线程抢先修改，则当前节点成功添加到尾部
    if (compareAndSetTail(pred, node)) {
      pred.next = node;
      return node;
    }
  }
  //若失败，循环直到成功
  enq(node);
  return node;
}

private Node enq(final Node node) {
  for (;;) {
    Node t = tail;
    //此时链表为空，初始化双链表
    if (t == null) { // Must initialize
      if (compareAndSetHead(new Node()))
        tail = head;
    } else {
      //将node添加到链表尾部，直到成功返回
      node.prev = t;
      if (compareAndSetTail(t, node)) {
        t.next = node;
        return t;
      }
    }
  }
}

```

sss

``` java

final boolean acquireQueued(final Node node, int arg) {
  boolean failed = true;
  try {
    boolean interrupted = false;
    for (;;) {
      //获取前节点，如果是表头则尝试获取锁
      final Node p = node.predecessor();
      if (p == head && tryAcquire(arg)) {
                //若成功，则将当前节点设置为表头
                setHead(node);
                p.next = null; // help GC
                failed = false;
                return interrupted;
            }
            //检查抢占锁失败的线程是否要被挂起，以及是否被中断
            if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                interrupted = true;
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}

```

sss
``` java
static final class NonfairSync extends Sync {
  private static final long serialVersionUID = 7316153563782823691L;

  final void lock() {
    //
    if (compareAndSetState(0, 1))
        setExclusiveOwnerThread(Thread.currentThread());
    else
        acquire(1);
  }

  protected final boolean tryAcquire(int acquires) {
    return nonfairTryAcquire(acquires);
  }
}
```
