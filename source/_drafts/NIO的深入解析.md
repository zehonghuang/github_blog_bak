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

先回顾一下几个相关函数，JVM相关实现可以看Net.c源码，这里不做赘述。
``` c
// domain : AF_UNIX|AF_LOCAL 本地传输，AF_INET|AF_INET6  ipv4/6传输
// type : SOCK_STREAM -> TCP, SOCK_DGRAM -> UDP
// protocol : 0 系统默认
// return : socket fd
int socket(int domain, int type, int protocol);
//sockfd : socket retuen fd
//addr : sockaddr_in{sin_family=AF_INET -> ipv4,s_addr -> ip地址,sin_port -> 端口号}
//addrlen : sockaddr的长度
int bind(int sockfd, struct sockaddr* addr, int addrlen);
//backlog : 最大连接数， syn queue + accpet queue 的大小
int listen(int sockfd, int backlog);
//同bind()的参数
int accept(int sockfd, struct sockaddr addr, socklen_t addrlen);
int connect(int sd, struct sockaddr *server, int addr_len);
```
另，socketIO可以使用`read & write`，和`recv & send`两种函数，后者多了一个参数flags。
注，阻塞非阻塞模式，以下函数返回值有所区别。
```c
int write(int fd, void *buf, size_t nbytes);
int read(int fd, void *buf, size_t nbytes);
//flags：
//MSG_DONTROUTE 本地网络，不需查找路由
//MSG_OOB TCP URG紧急指针，多用于心跳
//MSG_PEEK  只读不取，数据保留在缓冲区
//MSG_WAITALL 等待到满足指定条件才返回，在此之前会一直阻塞
int recv(int sockfd,void *buf,int len,int flags);
int send(int sockfd,void *buf,int len,int flags);
```

#### IO多路复用
##### epoll
##### kqueue


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
