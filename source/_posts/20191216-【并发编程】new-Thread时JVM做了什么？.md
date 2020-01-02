---
title: 【Java并发】new Thread时JVM做了什么？
date: 2019-12-11 11:48:28
tags:
  - java
  - C/C++
  - Thread
  - Linux编程
  - 并发编程
categories:
    - java并发编程
---

最近兴致勃勃捡起C++，打算深入Unix网络编程，但输出博客想从比较简单的问题入手，所以对标一下Java与C的线程创建过程，加深一下理解。（注，Linux）

---

Java创建线程是简单的，`new Thread()`和`start()`即可启动并执行线程，但由于posix提供的api还涉及不少线程属性，真实过程显然要复杂得多。可以看到前者`new Thread`只是初始化属性，后者才是真正意义上调用本地接口`JVM_StartThread`，创建线程。

``` c++
//以下函数指针均被定义在jvm.h，实现在jvm.cpp
static JNINativeMethod methods[] = {
    {"start0",           "()V",        (void *)&JVM_StartThread},
    {"stop0",            "(" OBJ ")V", (void *)&JVM_StopThread},
    {"isAlive",          "()Z",        (void *)&JVM_IsThreadAlive},
    {"suspend0",         "()V",        (void *)&JVM_SuspendThread},
    {"resume0",          "()V",        (void *)&JVM_ResumeThread},
    {"setPriority0",     "(I)V",       (void *)&JVM_SetThreadPriority},
    {"yield",            "()V",        (void *)&JVM_Yield},
    {"sleep",            "(J)V",       (void *)&JVM_Sleep},
    {"currentThread",    "()" THD,     (void *)&JVM_CurrentThread},
    {"countStackFrames", "()I",        (void *)&JVM_CountStackFrames},
    {"interrupt0",       "()V",        (void *)&JVM_Interrupt},
    {"isInterrupted",    "(Z)Z",       (void *)&JVM_IsInterrupted},
    {"holdsLock",        "(" OBJ ")Z", (void *)&JVM_HoldsLock},
    {"getThreads",        "()[" THD,   (void *)&JVM_GetAllThreads},
    {"dumpThreads",      "([" THD ")[[" STE, (void *)&JVM_DumpThreads},
    {"setNativeName",    "(" STR ")V", (void *)&JVM_SetNativeThreadName},
};
```

阅读相关JVM源码时，需要知道几个重要类的关系，下面部分实现默认os_linux.cpp。
```
1、JavaThread: 创建线程执行任务，持有java_lang_thread & OSThread对象，维护线程状态运行Thread.run()的地方
2、OSThread: 由于不同操作系统的状态不一致，所以JVM维护了一套平台线程状态，被JavaThread所持有
3、java_lang_Thread::ThreadStatus: 即Java线程状态，与java.lang.Thread.State完全一致
4、OSThread::ThreadState: 2所说的平台线程状态
```

需要说的是，以下相关pthread函数均是posix标准，可自行阅读<pthread.h>文档，不多赘述。
``` c++
JVM_ENTRY(void, JVM_StartThread(JNIEnv* env, jobject jthread))
  JVMWrapper("JVM_StartThread");
  JavaThread *native_thread = NULL;

  bool throw_illegal_thread_state = false;

  //这里一对花括号代表一段程序，执行完后回释放资源，会调用~MutexLocker(Monitor * monitor)释放互斥锁 (注，~代表析构函数)
  {
    //获取互斥锁，加上诉说明，等同于synchronized代码块
    //这里的独占锁依然使用了pthread_mutex_lock函数
    //具体实现在os_posix.cpp的PlatformEvent.park & unpark函数
    MutexLocker mu(Threads_lock);

    //这里检查Thread.java的long eetop变量是否有值，避免重复启动线程，该值为JavaThread的地址
    if (java_lang_Thread::thread(JNIHandles::resolve_non_null(jthread)) != NULL) {
      throw_illegal_thread_state = true;
    } else {
      //实例化Thread时，可以设置stackSize，用于初始化虚拟地址栈空间
      jlong size =
             java_lang_Thread::stackSize(JNIHandles::resolve_non_null(jthread));

      NOT_LP64(if (size > SIZE_MAX) size = SIZE_MAX;)
      size_t sz = size > 0 ? (size_t) size : 0;
      //这里正式调用pthread_create创建线程
      native_thread = new JavaThread(&thread_entry, sz);

      //可能因为内存不足，无法为OSThread分配空间，所以可能为NULL
      if (native_thread->osthread() != NULL) {
        //上面提到的eetop，将在这里被设置
        native_thread->prepare(jthread);
      }
    }
  }

  if (throw_illegal_thread_state) {
    THROW(vmSymbols::java_lang_IllegalThreadStateException());
  }

  assert(native_thread != NULL, "Starting null thread?");

  if (native_thread->osthread() == NULL) {
    // 安全内存回收(SMR)
    native_thread->smr_delete();
    if (JvmtiExport::should_post_resource_exhausted()) {
      JvmtiExport::post_resource_exhausted(
        JVMTI_RESOURCE_EXHAUSTED_OOM_ERROR | JVMTI_RESOURCE_EXHAUSTED_THREADS,
        os::native_thread_creation_failed_msg());
    }
    THROW_MSG(vmSymbols::java_lang_OutOfMemoryError(),
              os::native_thread_creation_failed_msg());
  }
  //哦吼！这是线程真正的开始
  Thread::start(native_thread);

JVM_END
```

我们知道`pthread_create`创建线程后立刻执行线程，所以什么`Thread::start`才是真正启动线程，我们需要进一步窥探。
``` c++
//JavaThread类定义在thread.hpp中，为Thread的子类
JavaThread::JavaThread(ThreadFunction entry_point, size_t stack_sz) :
                       Thread() {
  //初始化字段，最重要的是创建线程安全点，作用在垃圾回收时的STW
  initialize();
  _jni_attach_state = _not_attaching_via_jni;
  set_entry_point(entry_point);
  //yep，线程类型有gc、编译、守护、平台等几种
  os::ThreadType thr_type = os::java_thread;
  thr_type = entry_point == &compiler_thread_entry ? os::compiler_thread :
                                                     os::java_thread;
  os::create_thread(this, thr_type, stack_sz);
  //这段话我没懂，有大佬明白可以交流下
  // The _osthread may be NULL here because we ran out of memory (too many threads active).
  // We need to throw and OutOfMemoryError - however we cannot do this here because the caller
  // may hold a lock and all locks must be unlocked before throwing the exception (throwing
  // the exception consists of creating the exception object & initializing it, initialization
  // will leave the VM via a JavaCall and then all locks must be unlocked).
  //
  // The thread is still suspended when we reach here. Thread must be explicit started
  // by creator! Furthermore, the thread must also explicitly be added to the Threads list
  // by calling Threads:add. The reason why this is not done here, is because the thread
  // object must be fully initialized (take a look at JVM_Start)
}
```

`create_thread`对线程属性的设置跟日常写c++时有些不同，包括警戒线缓冲区和页面对其，一般我们并不会考虑aligned。
``` c++
//os_linux.cpp

bool os::create_thread(Thread* thread, ThreadType thr_type,
                       size_t req_stack_size) {
  assert(thread->osthread() == NULL, "caller responsible");

  // Allocate the OSThread object (<_<)可能空指针
  OSThread* osthread = new OSThread(NULL, NULL);
  if (osthread == NULL) {
    return false;
  }

  // java_thread
  osthread->set_thread_type(thr_type);

  // Initial state is ALLOCATED but not INITIALIZED
  osthread->set_state(ALLOCATED);

  thread->set_osthread(osthread);

  pthread_attr_t attr;
  pthread_attr_init(&attr);
  // 所以java线程都是分离状态，join也并非用结合状态
  pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

  // -Xss默认1M，Thread没设置stackSize，在Linux-x86默认512K，取最大值
  size_t stack_size = os::Posix::get_initial_stack_size(thr_type, req_stack_size);
  //这里设置栈警戒缓冲区，默认系统页大小
  //原注解的意思是，Linux的NPTL没有完全按照posix标准
  //理应guard_size + stack_size，且二者大小相等，而不是从stack_size取guard_size作为警戒取
  //所以这里模仿实现posix标准
  size_t guard_size = os::Linux::default_guard_size(thr_type);
  if (stack_size <= SIZE_MAX - guard_size) {
    stack_size += guard_size;
  }
  assert(is_aligned(stack_size, os::vm_page_size()), "stack_size not aligned");

  int status = pthread_attr_setstacksize(&attr, stack_size);
  assert_status(status == 0, status, "pthread_attr_setstacksize");

  pthread_attr_setguardsize(&attr, os::Linux::default_guard_size(thr_type));

  ThreadState state;

  {
    //欧了，创建线程，函数指针thread_native_entry是重点
    pthread_t tid;
    int ret = pthread_create(&tid, &attr, (void* (*)(void*)) thread_native_entry, thread);
    
    pthread_attr_destroy(&attr);

    if (ret != 0) {
      // Need to clean up stuff we've allocated so far
      thread->set_osthread(NULL);
      delete osthread;
      return false;
    }

    // Store pthread info into the OSThread
    osthread->set_pthread_id(tid);

    // 等待thread_native_entry设置osthread为INITIALIZED，或收到终止信号
    {
      Monitor* sync_with_child = osthread->startThread_lock();
      MutexLockerEx ml(sync_with_child, Mutex::_no_safepoint_check_flag);
      while ((state = osthread->get_state()) == ALLOCATED) {
        sync_with_child->wait(Mutex::_no_safepoint_check_flag);
      }
    }
  }
    // Aborted due to thread limit being reached
  if (state == ZOMBIE) {
    thread->set_osthread(NULL);
    delete osthread;
    return false;
  }

  // The thread is returned suspended (in state INITIALIZED),
  // and is started higher up in the call chain
  assert(state == INITIALIZED, "race condition");
  return true;
}
```

由于`pthread_create`会立即执行`thread_native_entry`，但又因为JavaThread被OSThread管理着，所以需要加各种排斥锁，达到二者状态同步的效果。
``` c++
static void *thread_native_entry(Thread *thread) {

  thread->record_stack_base_and_size();
  //我没理解这里的左右，有CPU大佬请解答
  // Try to randomize the cache line index of hot stack frames.
  // This helps when threads of the same stack traces evict each other's
  // cache lines. The threads can be either from the same JVM instance, or
  // from different JVM instances. The benefit is especially true for
  // processors with hyperthreading technology.
  static int counter = 0;
  int pid = os::current_process_id();
  alloca(((pid ^ counter++) & 7) * 128);
  //声明类似ThreadLocal的pthread_key_t
  thread->initialize_thread_current();

  OSThread* osthread = thread->osthread();
  Monitor* sync = osthread->startThread_lock();

  osthread->set_thread_id(os::current_thread_id());

  if (UseNUMA) {
    int lgrp_id = os::numa_get_group_id();
    if (lgrp_id != -1) {
      thread->set_lgrp_id(lgrp_id);
    }
  }
  // 屏蔽来自VM的阻塞信号
  os::Linux::hotspot_sigmask(thread);

  // initialize floating point control register
  os::Linux::init_thread_fpu_state();

  {
    MutexLockerEx ml(sync, Mutex::_no_safepoint_check_flag);

    // notify parent thread
    osthread->set_state(INITIALIZED);
    sync->notify_all();

    // wait until os::start_thread() <<<------  自璇中，等待调用Thread::start()
    while (osthread->get_state() == INITIALIZED) {
      sync->wait(Mutex::_no_safepoint_check_flag);
    }
  }

  assert(osthread->pthread_id() != 0, "pthread_id was not set as expected");

  // call one more level start routine
  thread->call_run(); // <--- 里面调用JavaThread::run()

  // Note: at this point the thread object may already have deleted itself.
  // Prevent dereferencing it from here on out.
  thread = NULL;

  return 0;
}
```

执行Runable之前，JVM需要给java线程分配本地缓冲区等操作(这是一个大块)，这里算是到头了。
``` c++
void JavaThread::run() {
  // 初始化TLAB，即在年轻代割一点空间给自己，具体大小-XX:UseTLAB设置
  this->initialize_tlab();

  //不知道干嘛的，在linux_x86是空实现
  this->record_base_of_stack_pointer();

  this->create_stack_guard_pages();

  this->cache_global_variables();

  // Thread is now sufficiently initialized to be handled by the safepoint code as being
  // in the VM. Change thread state from _thread_new to _thread_in_vm
  ThreadStateTransition::transition_and_fence(this, _thread_new, _thread_in_vm);

  assert(JavaThread::current() == this, "sanity check");
  assert(!Thread::current()->owns_locks(), "sanity check");

  DTRACE_THREAD_PROBE(start, this);

  this->set_active_handles(JNIHandleBlock::allocate_block());

  if (JvmtiExport::should_post_thread_life()) {
    JvmtiExport::post_thread_start(this);

  }
  //这里才是真正调用java.lang.Thread#run()方法，执行Runable
  // We call another function to do the rest so we are sure that the stack addresses used
  // from there will be lower than the stack base just computed.
  thread_main_inner();
}
```

下面代码不多做解释了，`this->entry_point()(this, this)` 等同于调用函数`thread_entry`，`JavaCalls`也是个大块，复杂调用java方法。
``` c++
void JavaThread::thread_main_inner() {
  assert(JavaThread::current() == this, "sanity check");
  assert(this->threadObj() != NULL, "just checking");

  // Execute thread entry point unless this thread has a pending exception
  // or has been stopped before starting.
  // Note: Due to JVM_StopThread we can have pending exceptions already!
  if (!this->has_pending_exception() &&
      !java_lang_Thread::is_stillborn(this->threadObj())) {
    {
      ResourceMark rm(this);
      this->set_native_thread_name(this->get_thread_name());
    }
    HandleMark hm(this);
    this->entry_point()(this, this);
  }

  DTRACE_THREAD_PROBE(stop, this);

  // Cleanup is handled in post_run()
}

static void thread_entry(JavaThread* thread, TRAPS) {
  HandleMark hm(THREAD);
  Handle obj(THREAD, thread->threadObj());
  JavaValue result(T_VOID);
  JavaCalls::call_virtual(&result,
                          obj,
                          SystemDictionary::Thread_klass(),
                          vmSymbols::run_method_name(),
                          vmSymbols::void_method_signature(),
                          THREAD);
}
```

总体来说，创建一个线程对于JVM来说还是相对费劲的，不是说性能不好，是需要做太多事。与GC息息相关的两个点就是TLAB与ThreadSafePoint，其他则是对于java程序员透明的栈空间的分配(这里指的是虚拟内存地址)、线程状态管理。