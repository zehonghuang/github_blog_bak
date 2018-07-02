---
title: 【Java并发】JUC—ReentrantReadWriteLock有坑，小心读锁！
date: 2018-07-02 20:05:59
tags:
  - java　
  - 并发编程
  - 同步
  - 读写锁
  - 线程饥渴
---

好长一段时间前，某写场景需要JUC的读写锁，但在某个时刻内读写线程都报超时预警（长时间无响应），看起来像是锁竞争过程中出现死锁（我猜）。经过排查项目并没有能造成死锁的可以之处，因为业务代码并不复杂（仅仅是一个计算过程），经几番折腾，把注意力转移到JDK源码，正文详细说下ReentrantReadWriteLock的隐藏坑点。

---

过程大致如下：
- 若干个读写线程抢占读写锁
- 读线程手脚快，优先抢占到读锁（其中少数线程任务较重，执行时间较长）
- 写线程随即尝试获取写锁，未成功，进入双列表进行等待
- 随后读线程也进来了，要去拿读锁

问题：优先得到锁的读线程执行时间长达73秒，该时段写线程等待是理所当然的，那读线程也应该能够得到读锁才对，因为是共享锁，是吧？但预警结果并不是如此，超时任务线程中大部分为读。

把场景简化为如下的测试代码：读——写——读 线程依次尝试获取ReadWriteLock，用空转替换执行时间过长。

``` Java
public class ReadWriteLockTest {
  public static void main(String[] args) {
    ReadWriteLockTest readWriteLockTest = new ReadWriteLockTest();
  }

  public ReadWriteLockTest() {
    try {
      init();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  void init() throws InterruptedException {
    TestLock testLock = new TestLock();
    Thread read1 = new Thread(new ReadThread(testLock), "读线程 -- 1");
    read1.start();
    Thread.sleep(100);
    Thread write = new Thread(new WriteThread(testLock), "写线程 -- 1");
    write.start();
    Thread.sleep(100);
    Thread read2 = new Thread(new ReadThread(testLock), "读线程 -- 2");
    read2.start();
  }

  private class TestLock {

    private String string = null;
    private ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private Lock readLock = readWriteLock.readLock();
    private Lock writeLock = readWriteLock.writeLock();

    public void set(String s) {
      writeLock.lock();
      try {
//                writeLock.tryLock(10, TimeUnit.SECONDS);
        string = s;
      } finally {
        writeLock.unlock();
      }
    }

    public String getString() {
      readLock.lock();
      System.out.println(Thread.currentThread());
      try {
        while (true) {

        }
      } finally {
        readLock.unlock();
      }
    }
  }

  class WriteThread implements Runnable {

    private TestLock testLock;
    public WriteThread(TestLock testLock) {
      this.testLock = testLock;
    }

    @Override
    public void run() {
      testLock.set("射不进去，怎么办？");
    }
  }

  class ReadThread implements Runnable {

    private TestLock testLock;
    public ReadThread(TestLock testLock) {
      this.testLock = testLock;
    }

    @Override
    public void run() {
      testLock.getString();
    }
  }
}

```

![jstack](http://p4ygo03xz.bkt.clouddn.com/github-blog/image/jstack.png)
