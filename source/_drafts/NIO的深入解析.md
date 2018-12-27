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
