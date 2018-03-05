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
  try {
    for(int j = 0; j < 10; j++) {
      count++;
    }
  } finally {
    lock.unlock();
  }
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

OK！上面已经知道了Lock是怎么实现可重入性的，现在了解一下怎么管理抢占失败而被挂起的线程。

看到AQS的内部类Node，即可知道是一个双链表，表头Head代表当前占有锁的线程，抢占失败的线程将被添加到尾部。
![双链表](http://p4ygo03xz.bkt.clouddn.com/github-blog/image/AbstractQueuedSynchronizer-Node.png)

可以看到Node都有类型和挂起状态，作用于实现各类锁。在上面的代码可以看到acquire方法中通过`addWaiter`方法将新节点添加到尾部的。
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
  //等待触发，即抢占不到锁被挂起
  static final int SIGNAL    = -1;
  //等待条件符合，需要等待Condition的signal唤醒，Lock提供了newCondition()，作用跟Object的wait()与notify()是一样的
  static final int CONDITION = -2;
  //节点状态向后传递，在共享锁有用处
  static final int PROPAGATE = -3;

  //挂起的状态
  volatile int waitStatus;
  volatile Node prev;
  volatile Node next;
  volatile Thread thread;
  //节点类型，是共享还是独占
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
这里我看一下AQS是怎么把线程添加到链表的。将当前tail设置为pred，如果非空，把pred设置为当前node的前节点prev，再将node设置到成员变量tail。如果tail为null，说明此刻双链表未初始化，进去`enq`。
``` java
private Node addWaiter(Node mode) {
  //创建当前线程为新节点
  Node node = new Node(Thread.currentThread(), mode);
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
    //第一个成功抢占锁的线程是不会被添加到双链表的，所以第二个线程进来时tail是空的
    if (t == null) {
      if (compareAndSetHead(new Node())) //存在多线程同时修改head的情况
        tail = head;
    } else {
      //如果此刻tail非空，将node添加到链表尾部，直到成功返回
      node.prev = t;
      if (compareAndSetTail(t, node)) {
        t.next = node;
        return t;
      }
    }
  }
}
```

上面的代码只展示了线程如何变成一个Node被塞进链表末端的，至关重要的线程挂起则在下面`acquireQueued`方法。挂起线程最终会调用到`LockSupport.park`这个静态方法，而park又调用了`Unsafe.park`这个本地方法。

acquireQueued的逻辑其实跟while里的wait是差不多的，一直在被无意义的循环，被挂起，直到当前节点被推到第二个节点，head结束后，被unpark唤醒后及时抢占锁(详见`unparkSuccessor`方法)

``` java

final boolean acquireQueued(final Node node, int arg) {
  boolean failed = true;
  try {
    boolean interrupted = false;
    for (;;) {
      //获取前节点，如果是表头则尝试获取锁
      final Node p = node.predecessor();
      if (p == head && tryAcquire(arg)) { //被挂起前最后一次挣扎，也许这时head已经完事了，就该轮到自己了
        //若成功，则将当前节点设置为表头
        setHead(node);
        p.next = null; // help GC
        failed = false;
        return interrupted;
      }
      //检查当前上一个节点是否应该被挂起
      if (shouldParkAfterFailedAcquire(p, node) &&
            //挂起线程以及检查是否中断
            parkAndCheckInterrupt())
        interrupted = true;
    }
  } finally {
    if (failed)
      cancelAcquire(node);
  }
}

private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
  int ws = pred.waitStatus;
  //上一个节点还在等待中，则返回true，该节点也要乖乖在队列中等待
  if (ws == Node.SIGNAL)
    return true;
  //把取消状态中的节点全部移除
  if (ws > 0) {
    do {
      node.prev = pred = pred.prev;
    } while (pred.waitStatus > 0);
    pred.next = node;
  } else {
    //这一步没有理解明白，但应该是再一次重试获取，确保被挂起之前不能获取到锁，如果还是失败，下一轮循环将被挂起
    compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
  }
  return false;
}

```


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
