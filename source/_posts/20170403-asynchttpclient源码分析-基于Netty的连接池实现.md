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

这里暂且仅关注连接池的实现，部分涉及ahc的请求过程，以及Netty的channel输入输出处理、哈希轮定时器算法、事件轮询方式的区别，又或者信号量的使用等等，以后有机会会拿出来单独详解。

``` java
public class HttpTest {
    static AsyncHttpClient asyncHttpClient = Dsl
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
            //重载删除方法，因为删除channel时，需要是否信号量
            @Override
            public boolean remove(Object o) {
                boolean removed = super.remove(o);
                if (removed) {
                    if (maxTotalConnectionsEnabled)
                        freeChannels.release();
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

    // check if external EventLoopGroup is defined
    ThreadFactory threadFactory = config.getThreadFactory() != null ? config.getThreadFactory() : new DefaultThreadFactory(config.getThreadPoolName());
    allowReleaseEventLoopGroup = config.getEventLoopGroup() == null;
    ChannelFactory<? extends Channel> channelFactory;
    if (allowReleaseEventLoopGroup) {
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
        eventLoopGroup = config.getEventLoopGroup();
        if (eventLoopGroup instanceof OioEventLoopGroup)
            throw new IllegalArgumentException("Oio is not supported");
        if (eventLoopGroup instanceof NioEventLoopGroup) {
            channelFactory = NioSocketChannelFactory.INSTANCE;
        } else {
            channelFactory = getEpollSocketChannelFactory();
        }
    }
    //用于http请求的bootstrap
    httpBootstrap = newBootstrap(channelFactory, eventLoopGroup, config);
    //用于WebSocket请求的bootstrap
    wsBootstrap = newBootstrap(channelFactory, eventLoopGroup, config);

    // for reactive streams
    httpBootstrap.option(ChannelOption.AUTO_READ, false);
  }
}
```

对于用Netty实现网络客户端来说，这个配置很有参考价值，所以也贴上来一起观赏！
``` java
private Bootstrap newBootstrap(ChannelFactory<? extends Channel> channelFactory, EventLoopGroup eventLoopGroup, AsyncHttpClientConfig config) {
    @SuppressWarnings("deprecation")
    Bootstrap bootstrap = new Bootstrap().channelFactory(channelFactory).group(eventLoopGroup)//
            .option(ChannelOption.ALLOCATOR, config.getAllocator() != null ? config.getAllocator() : ByteBufAllocator.DEFAULT)//
            .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())//
            .option(ChannelOption.SO_REUSEADDR, config.isSoReuseAddress())//
            .option(ChannelOption.AUTO_CLOSE, false);
    if (config.getConnectTimeout() > 0) {
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
    }
    if (config.getSoLinger() >= 0) {
        bootstrap.option(ChannelOption.SO_LINGER, config.getSoLinger());
    }
    if (config.getSoSndBuf() >= 0) {
        bootstrap.option(ChannelOption.SO_SNDBUF, config.getSoSndBuf());
    }
    if (config.getSoRcvBuf() >= 0) {
        bootstrap.option(ChannelOption.SO_RCVBUF, config.getSoRcvBuf());
    }
    for (Entry<ChannelOption<Object>, Object> entry : config.getChannelOptions().entrySet()) {
        bootstrap.option(entry.getKey(), entry.getValue());
    }
    return bootstrap;
}
```
