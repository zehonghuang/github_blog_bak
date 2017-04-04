---
title: 20170403-asynchttpclient源码分析-基于Netty的连接池实现
date: 2017-04-03 17:53:18
tags:
  - Netty
  - 源码分析
  - NIO
categories:
  - Netty
---

最近项目重构，有了个机会更多接触一个有别于HttpAsyncClient的异步网络框架AsyncHttpClient，是个基于Netty的支持Http(s)或WebSocket协议的客户端。这东西有很多有趣的地方，特别是连接池的实现简单易懂，没有apache.hc的连接池实现那么蛋疼。如果想深入了解Netty用法的话，这是个不错的案例，很值得深究！

---

这里暂且仅关注连接池的实现，部分涉及Netty的channel输入输出处理、哈希轮定时器算法、事件轮询方式的区别，又或者信号量的使用等等，以后有机会会单独拿出来详解。

由于是基Netty的实现的，所以连接池实际上就是对channel的管理控制，有趣的是整个管理只用到了信号量+一个定时检测器，略微复杂的也就定时检测的逻辑，其实现方式简单且很好理解，不像httpclient里各种队列各种信号量难以理解。

先上一个简单的例子，事实上使用起来也不复杂。
``` java
public class HttpTest {
    static AsyncHttpClient asyncHttpClient = Dsl
            //实例化所有池和检测器
            .asyncHttpClient(
                    Dsl.config()
                    .setMaxConnections(500)
                    .setMaxConnectionsPerHost(50)
                    .setPooledConnectionIdleTimeout(6000)
                    .setConnectionTtl(500)
                    .setIoThreadsCount(100)
                    .setConnectTimeout(60000)
                    .setUseNativeTransport(
                            System.getProperty("os.name").toLowerCase().indexOf("linux") > 0));

    public static void main(String[] args) throws Exception {
        List<Param> params = new ArrayList<>();
        params.add(new Param("keyfrom", "XXX"));

        asyncHttpClient
                .prepareGet("http://fanyi.youdao.com/openapi.do")
//                .setSingleHeaders(singleHeaders)
//                .setBodyParts(parts)
                .addQueryParams(params)
                //这里进入发送请求阶段
                .execute()
                .toCompletableFuture()
                //超时报错，或请求异常，做容错处理，抛出一个Response
                .exceptionally(t -> {
                    return new Response() {...};
                })
                .thenAccept(rep -> System.out.println("RESPONSE BODY" + rep.getResponseBody()));
    }
}
```

先看看`DefaultAsyncHttpClientConfig`类的配置参数，这里只列出本文所需要的参数。有一点值得提一下，如果想了解Java怎么像clojure或者scala一样创建不可变对象，可以看看这个类的写法。
``` java
// timeouts
//连接超时
private final int connectTimeout;
//请求超时
private final int requestTimeout;
//读取超时
private final int readTimeout;
//关闭Client前的静默时间
private final int shutdownQuietPeriod;
//关闭超时
private final int shutdownTimeout;

// keep-alive
private final boolean keepAlive;
//连接池空闲时间
private final int pooledConnectionIdleTimeout;
//定时清理空闲连接的时间
private final int connectionPoolCleanerPeriod;
//连接存活时间
private final int connectionTtl;
//最大连接数
private final int maxConnections;
//每个路由的最大连接数
private final int maxConnectionsPerHost;
//用于channel超时处理
private final ChannelPool channelPool;
private final KeepAliveStrategy keepAliveStrategy;


// internals
private final String threadPoolName;
private final int httpClientCodecMaxInitialLineLength;
private final int httpClientCodecMaxHeaderSize;
private final int httpClientCodecMaxChunkSize;
private final int chunkedFileChunkSize;
private final int webSocketMaxBufferSize;
private final int webSocketMaxFrameSize;
private final Map<ChannelOption<Object>, Object> channelOptions;
//时间轮询组类型
private final EventLoopGroup eventLoopGroup;
//是否用epoll，仅linux系统支持
private final boolean useNativeTransport;
//用于Timeout处理，建议用默认Netty的HashedWheelTimer
private final Timer nettyTimer;
private final ThreadFactory threadFactory;
private final AdditionalChannelInitializer httpAdditionalChannelInitializer;
private final AdditionalChannelInitializer wsAdditionalChannelInitializer;
private final ResponseBodyPartFactory responseBodyPartFactory;
//其实就是EventLoopGroup指定的线程数
private final int ioThreadsCount;
```

就从这里开始，开头主要实例化`ChannelManager`和`NettyRequestSender`以及`Timer`三个重要组件，`NettyRequestSender`用于发送请求以及向`ChannelManager`索取channel使用权，`Timer`则负责另外两个组件给他的检测任务。
``` java
public final class Dsl {
  public static AsyncHttpClient asyncHttpClient(DefaultAsyncHttpClientConfig.Builder configBuilder) {
    //默认客户端
    return new DefaultAsyncHttpClient(configBuilder.build());
  }
  //...
  //...
}

public class DefaultAsyncHttpClient implements AsyncHttpClient {
  private final AsyncHttpClientConfig config;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  //Netty初始化的起点，Bootstrap与Channel池管理都在这里声明
  private final ChannelManager channelManager;
  //发送请求，以及向ChannelManager索取channel的使用权
  private final NettyRequestSender requestSender;
  private final boolean allowStopNettyTimer;
  //定时做超时处理
  private final Timer nettyTimer;

  public DefaultAsyncHttpClient(AsyncHttpClientConfig config) {

    this.config = config;

    allowStopNettyTimer = config.getNettyTimer() == null;
    //默认使用HashedWheelTimer
    nettyTimer = allowStopNettyTimer ? newNettyTimer() :config.getNettyTimer();
    //下面两个是重点！！！
    channelManager = new ChannelManager(config, nettyTimer);
    requestSender = new NettyRequestSender(config, channelManager,nettyTimer, new AsyncHttpClientState(closed));
    //给Bootstraps配置解析器，以及处理接收服务端发送的消息的处理器
    channelManager.configureBootstraps(requestSender);
  }
  private Timer newNettyTimer() {
    HashedWheelTimer timer = new HashedWheelTimer();
    timer.start();
    return timer;
  }
}
```

这里是重中之重，网络配置、连接池、IO线程池、轮询方式都是在这儿产生的。
``` java
public class ChannelManager {
  private final AsyncHttpClientConfig config;
  private final EventLoopGroup eventLoopGroup;
  private final boolean allowReleaseEventLoopGroup;
  private final Bootstrap httpBootstrap;
  private final Bootstrap wsBootstrap;
  private final long handshakeTimeout;
  private final IOException tooManyConnections;
  private final IOException tooManyConnectionsPerHost;

  //定时清理不符合标准的channel
  private final ChannelPool channelPool;
  //netty自带的用于管理channel的管理器
  private final ChannelGroup openChannels;
  private final ConcurrentHashMap<Channel, Object> channelId2PartitionKey = new ConcurrentHashMap<>();
  //是否开启最大总连接数
  private final boolean maxTotalConnectionsEnabled;
  //最大连接数
  private final Semaphore freeChannels;
  //是否开启每个路由最大连接数
  private final boolean maxConnectionsPerHostEnabled;
  //每个路由最大连接数
  private final ConcurrentHashMap<Object, Semaphore> freeChannelsPerHost = new ConcurrentHashMap<>();

  private AsyncHttpClientHandler wsHandler;

  public ChannelManager(final AsyncHttpClientConfig config, Timer nettyTimer) {

    this.config = config;
    //忽略一小段关于ssl的
    //ChannelPool是用于检测已经实例化的channel的健康状况，如果不合格会直接close掉
    ChannelPool channelPool = config.getChannelPool();
    if (channelPool == null) {
        if (config.isKeepAlive()) {
            //这是默认使用的，事实上多数场景不需要我们自己实现
            channelPool = new DefaultChannelPool(config, nettyTimer);
        } else {
            channelPool = NoopChannelPool.INSTANCE;
        }
    }
    this.channelPool = channelPool;

    tooManyConnections = trimStackTrace(new TooManyConnectionsException(config.getMaxConnections()));
    tooManyConnectionsPerHost = trimStackTrace(new TooManyConnectionsPerHostException(config.getMaxConnectionsPerHost()));
    maxTotalConnectionsEnabled = config.getMaxConnections() > 0;
    maxConnectionsPerHostEnabled = config.getMaxConnectionsPerHost() > 0;

    if (maxTotalConnectionsEnabled || maxConnectionsPerHostEnabled) {
        //管理已经被实例化的channel
        openChannels = new DefaultChannelGroup("asyncHttpClient", GlobalEventExecutor.INSTANCE) {
            //重载删除方法，因为删除channel时，需要释放信号量
            @Override
            public boolean remove(Object o) {
                boolean removed = super.remove(o);
                if (removed) {
                    //释放总连接池的信号量
                    if (maxTotalConnectionsEnabled)
                        freeChannels.release();
                    //释放路由连接池的信号量
                    if (maxConnectionsPerHostEnabled) {
                        Object partitionKey = channelId2PartitionKey.remove(Channel.class.cast(o));
                        if (partitionKey != null) {
                            Semaphore hostFreeChannels = freeChannelsPerHost.get(partitionKey);
                            if (hostFreeChannels != null)
                                hostFreeChannels.release();
                        }
                    }
                }
                return removed;
            }
        };
        //信号量数为最大连接数
        freeChannels = new Semaphore(config.getMaxConnections());
    } else {
        openChannels = new DefaultChannelGroup("asyncHttpClient", GlobalEventExecutor.INSTANCE);
        freeChannels = null;
    }

    handshakeTimeout = config.getHandshakeTimeout();

    ThreadFactory threadFactory = config.getThreadFactory() != null ? config.getThreadFactory() : new DefaultThreadFactory(config.getThreadPoolName());
    allowReleaseEventLoopGroup = config.getEventLoopGroup() == null;
    ChannelFactory<? extends Channel> channelFactory;
    if (allowReleaseEventLoopGroup) {
        //这个只能在linux下使用
        if (config.isUseNativeTransport()) {
            eventLoopGroup = newEpollEventLoopGroup(config.getIoThreadsCount(), threadFactory);
            channelFactory = getEpollSocketChannelFactory();
        } else {
            //通常默认走这个！
            eventLoopGroup = new NioEventLoopGroup(config.getIoThreadsCount(), //瞧！IO线程数就是时间轮询的线程数
              threadFactory);
            channelFactory = NioSocketChannelFactory.INSTANCE;
        }
    } else {
        //...
    }
    //用于http请求的bootstrap
    httpBootstrap = newBootstrap(channelFactory, eventLoopGroup, config);
    //用于WebSocket请求的bootstrap
    wsBootstrap = newBootstrap(channelFactory, eventLoopGroup, config);

    httpBootstrap.option(ChannelOption.AUTO_READ, false);
  }
}
```

实例化完`ChannelManager`后，就轮到请求发送器，这里先看看所需要的参数，具体执行的方法在后面说。
``` java
public final class NettyRequestSender {
  private final AsyncHttpClientConfig config;
  private final ChannelManager channelManager;
  private final Timer nettyTimer;
  private final AsyncHttpClientState clientState;
  private final NettyRequestFactory requestFactory;

  public NettyRequestSender(AsyncHttpClientConfig config,//
            ChannelManager channelManager,//
            Timer nettyTimer,//
            AsyncHttpClientState clientState) {
      this.config = config;
      this.channelManager = channelManager;
      this.nettyTimer = nettyTimer;
      this.clientState = clientState;
      requestFactory = new NettyRequestFactory(config);
  }
}
```

再回来看看`ChannelManager`构造方法中使用的工厂方法`newBootstrap(channelFactory, eventLoopGroup, config)`，这是支持整个ahc运作的代码，对于用Netty实现网络客户端来说，这个配置很有参考价值，所以也贴上来一起观赏！
``` java
private Bootstrap newBootstrap(ChannelFactory<? extends Channel> channelFactory, EventLoopGroup eventLoopGroup, AsyncHttpClientConfig config) {
    @SuppressWarnings("deprecation")
    Bootstrap bootstrap = new Bootstrap().channelFactory(channelFactory)
            //客户端只有worker线程池，ServerBootstrap则需要boss和worker
            .group(eventLoopGroup)
            //设置内存分配器，我的理解是关于堆内存模型的，可用于对Netty的优化
            .option(ChannelOption.ALLOCATOR, config.getAllocator() != null ? config.getAllocator() : ByteBufAllocator.DEFAULT)
            //是否使用tcp的Nagle算法，文件传输可以选择使用
            .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())
            //重复使用本地地址端口
            .option(ChannelOption.SO_REUSEADDR, config.isSoReuseAddress())//
            .option(ChannelOption.AUTO_CLOSE, false);
    if (config.getConnectTimeout() > 0) {
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
    }
    if (config.getSoLinger() >= 0) {
        //就是一个设置延迟关闭时间的参数，用于保证数据发送完成
        bootstrap.option(ChannelOption.SO_LINGER, config.getSoLinger());
    }
    if (config.getSoSndBuf() >= 0) {
        bootstrap.option(ChannelOption.SO_SNDBUF, config.getSoSndBuf());
    }
    if (config.getSoRcvBuf() >= 0) {
        bootstrap.option(ChannelOption.SO_RCVBUF, config.getSoRcvBuf());
    }
    //自定义配置
    for (Entry<ChannelOption<Object>, Object> entry : config.getChannelOptions().entrySet()) {
        bootstrap.option(entry.getKey(), entry.getValue());
    }
    return bootstrap;
}
```

``` java
public void configureBootstraps(NettyRequestSender requestSender) {
    final AsyncHttpClientHandler httpHandler = new HttpHandler(config, this, requestSender);
    wsHandler = new WebSocketHandler(config, this, requestSender);
    final NoopHandler pinnedEntry = new NoopHandler();
    final LoggingHandler loggingHandler = new LoggingHandler(LogLevel.TRACE);

    httpBootstrap.handler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline()//
                    .addLast(PINNED_ENTRY, pinnedEntry)//
                    .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())//
                    .addLast(INFLATER_HANDLER, newHttpContentDecompressor())//
                    .addLast(CHUNKED_WRITER_HANDLER, new ChunkedWriteHandler())//
                    .addLast(AHC_HTTP_HANDLER, httpHandler);
            if (config.getHttpAdditionalChannelInitializer() != null)
                config.getHttpAdditionalChannelInitializer().initChannel(ch);
        }
    });

    wsBootstrap.handler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline()//
                    .addLast(PINNED_ENTRY, pinnedEntry)//
                    .addLast(HTTP_CLIENT_CODEC, newHttpClientCodec())//
                    .addLast(AHC_WS_HANDLER, wsHandler);
            if (config.getWsAdditionalChannelInitializer() != null)
                config.getWsAdditionalChannelInitializer().initChannel(ch);
        }
    });
}
```
