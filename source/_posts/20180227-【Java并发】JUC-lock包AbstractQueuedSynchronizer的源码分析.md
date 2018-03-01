---
title: 【Java并发】JUC.lock包AbstractQueuedSynchronizer的源码分析
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

dddd

---

``` java

private transient volatile Node head;
private transient volatile Node tail;
private volatile int state;

public final void acquire(int arg) {
  //尝试获取锁，若失败，则进入等待队列;
  //acquireQueued将线程挂起，挂起异常则selfInterrupt中断当前线程
  if (!tryAcquire(arg) &&
          acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
      selfInterrupt();
}

protected boolean tryAcquire(int arg) {
  throw new UnsupportedOperationException();
}

protected boolean tryRelease(int arg) {
  throw new UnsupportedOperationException();
}

```
ss

``` java

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
