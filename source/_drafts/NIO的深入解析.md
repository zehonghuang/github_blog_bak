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
//flags：这里没打算展开讲，自行google
//MSG_DONTROUTE 本地网络，不需查找路由
//MSG_OOB TCP URG紧急指针，多用于心跳
//MSG_PEEK  只读不取，数据保留在缓冲区
//MSG_WAITALL 等待到满足指定条件才返回，在此之前会一直阻塞
int recv(int sockfd,void *buf,int len,int flags);
int send(int sockfd,void *buf,int len,int flags);
```

#### IO多路复用

NIO在不同操作系统提供了不同实现，win-select，linux-epoll以及mac-kqueue，本文忽略windows平台，只说linux & mac下的实现。

##### epoll
不太想讲epoll跟select的区别，网上多的是，不过唯一要说epoll本身是fd，很多功能都基于此，也不需要select一样重复实例化，下面的kqueue也是一样。

首先是epoll是个文件，所以有可能被其他epoll/select/poll监听，所以可能会出现循环或反向路径，内核实现极其复杂冗长，有兴趣可以啃下`ep_loop_check`和`reverse_path_check`，我图论学得不好，看不下去。
```c
typedef union epoll_data {
  void *ptr; //如果需要，可以携带自定义数据
  int fd; //被监听的事件
  __uint32_t u32;
  __uint64_t u64;
} epoll_data_t;

struct epoll_event {
  __uint32_t events;
  //EPOLLOUT：TL，缓冲池为空
  //EPOLLIN：TL，缓冲池为满
  //EPOLLET：EL，有所变化
  //还有其他，不一一列出了
  epoll_data_t data;
};
//size : 可监听的最大数目，后来2.6.8开始，此参数无效
//return : epoll fd
int epoll_create(int size);
//op : EPOLL_CTL_ADD, EPOLL_CTL_MOD, EPOLL_CTL_DEL 分别是新增修改删除fd
//fd : 被监听的事件
//event : 上面的struct
int epoll_ctl(int epfd, int op, int fd, struct epoll_event *event);
//events : 就绪事件的数组
//maxevents : 能被处理的最大事件数
//timeout : 0 非阻塞，-1 阻塞，>0 等待超时
int epoll_wait(int epfd, struct epoll_event * events, int maxevents, int timeout);
```

值得注意的是，epoll的边沿模式(EL)和水平模式(TL)，

`EL`只在中断信号来临时反馈，所以`buffer cache`的数据未处理完，没有新数据到来是不会通知就绪的。
`TL`则是会查看`buffer cache`是否还有数据，只要没有被处理完，会继续通知就绪。

一个关于这两种模式的问题，就EL模式是否必须把fd设置为NO_BLOCK。我不是很理解[Linux手册](http://man7.org/linux/man-pages/man7/epoll.7.html)中对EL的描述，为什么要和EL扯上关系，若是因为读写阻塞导致后续任务饥饿，那在TL是一样的后果。要我说，既然用了epoll，那就直接把fd设置未NO_BLOCK得了，就没那么多事。

对此我强烈建议写过一次linux下的网络编程，加强理解，这里不写示例了。

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
