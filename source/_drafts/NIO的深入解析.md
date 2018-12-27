---
title: 【网络编程】从Linux角度以及JVM源码，深入解析NIO
date: 2018-12-24 13:17:20
tags:
  - java
  - NIO
  - Linux内核
  - 网络编程
categories:
    - java网络编程
---

最近一段时间都在啃Linux内核， 也给了自己机会再度深入理解Java的NIO实现，希望能获得更多东西，尝试理解以前未能理解的，会涉及少量OpenJDK源码。

---

### 涉及的Linux知识

#### 文件描述符

对于Linux来说，一切皆为文件，设备文件、IO文件还是普通文件，都可以通过一个叫做文件描述符（FileDescriptor）的东西来进行操作，其涉及的数据结构可以自行了解VFS。

##### 设备阻塞与非阻塞

任意对设备的操作都是默认为阻塞的，如果没有或有不可操作的资源，会被添加到`wait_queue_head_t`中进行等待，直到被`semaphore`通知允许执行。此时可以通过`fcntl()`函数将文件描述符设置为非阻塞，若没有或有不可操作的资源，立即返回错误信息。

#### JVM内存结构 & 虚拟地址空间

众所周知，Linux下的每一进程都有自己的虚拟内存地址，而JVM也是一个进程，且JVM有自己的内存结构。既然如此，两者之间必有对应关系，OracleJDK7提供了NMT，用`jcmd pid VM.native_memory detail`可以查看各类区域的reserved，被committed的内存大小及其地址区间，再通过`pmap -p`可以看到进程内存信息。

肉眼对比地址区间可以发现，JVM heap是通过mmap分配内存的，位于进程的映射区内，而进程堆区可以被malloc进行分配，对应关系如图。
![jvm内存虚拟地址](https://raw.githubusercontent.com/zehonghuang/github_blog_bak/master/source/image/jvm%E8%99%9A%E6%8B%9F%E5%86%85%E5%AD%98%E5%9C%B0%E5%9D%80.png)

#### socket编程
- socket()
- listen()
- accpet()
- read() & write()
- 示例代码

- 涉及的Linux知识
  - 文件描述符
    - 设备的阻塞与非阻塞
  - JVM内存结构 & 虚拟地址空间
  - socket编程
    - socket()
    - listen()
    - accpet()
    - read() & write()
  - 文件IO编程
    - open()
    - read() & write()
    - lseek()
  - 多路复用IO
    - select - windows
    - epoll
      - epoll_create()
      - epoll_ctl()
      - epoll_wait()
    - kqueue
      - kqueue()
      - kevent()

- NIO源码
  - Selector
    - EPollSelectorImpl
    - KqueueSelectorImpl
  - Channels
    - 接口类型及其作用
    - 网络IO相关实现及其分析
      - ServerSocketChannel
      - SocketChannel
    - 文件IO
      - FileChannel
  - ByteBuffer
    - DirectByteBuffer
    - HeapByteBuffer
