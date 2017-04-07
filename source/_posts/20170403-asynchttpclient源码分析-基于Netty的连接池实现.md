---
title: asynchttpclient源码分析-基于Netty的连接池实现
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

**这里暂且仅关注连接池的实现，部分涉及Netty的channel输入输出处理、哈希轮定时器算法、事件轮询方式的区别，又或者信号量的使用等等，以后有机会会单独拿出来详解。**

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
//读取超时，含于请求时间
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
            //重写删除方法，因为删除channel时，需要释放信号量
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

//下面则是管道的配置
public void configureBootstraps(NettyRequestSender requestSender) {
    //ahc自定义的ChannelInboundHandler，异步方式获取服务端返回的数据
    //我们自己获取数据后的核心业务逻辑，也在这里开始
    final AsyncHttpClientHandler httpHandler = new HttpHandler(config, this, requestSender);
    wsHandler = new WebSocketHandler(config, this, requestSender);
    final NoopHandler pinnedEntry = new NoopHandler();

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

一切工作准备就绪，现在可以请求了！怎么构建请求就不打算讲了，可以自行阅读`RequestBuilderBase`类。执行`execute()`方法，正式开始请求，往下看`DefaultAsyncHttpClient.executeRequest()`怎么创建连接的。
``` java
public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {
  private final AsyncHttpClient client;
  public ListenableFuture<Response> execute() {
      return client.executeRequest(build(), new AsyncCompletionHandlerBase());
  }
}

public class DefaultAsyncHttpClient implements AsyncHttpClient {
  @Override
  public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) {
      if (config.getRequestFilters().isEmpty()) {
          return execute(request, handler);
      } else {
        //不考虑设置请求过滤器的情况
      }
  }
  private <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) {
      try {
          //把请求参数，和读取数据后的回调一同塞给请求发送器
          return requestSender.sendRequest(request, asyncHandler, null, false);
      } catch (Exception e) {
          asyncHandler.onThrowable(e);
          return new ListenableFuture.CompletedFailure<>(e);
      }
  }
}
```

OK～～上面列出`NettyRequestSender`需要什么参数，现在再来看看怎么做的？
下面的方法中，重点关注`sendRequestWithNewChannel`，它包括了如何新建channel、连接，抢占信号量
``` java
public <T> ListenableFuture<T> sendRequest(final Request request,//
            final AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean performingNextRequest) {
    //...
    ProxyServer proxyServer = getProxyServer(config, request);
    //使用SSL代理或者ws
    if (proxyServer != null && (request.getUri().isSecured() || request.getUri().isWebSocket()) && !isConnectDone(request, future))
        //暂时忽略另外两个创建连接的方式
    else
        //我们的例子用的是GET，所以执行该方法
        return sendRequestWithCertainForceConnect(request, asyncHandler, future, performingNextRequest, proxyServer, false);
}

private <T> ListenableFuture<T> sendRequestWithCertainForceConnect(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//注意，这时候传进来是null
            boolean performingNextRequest,//
            ProxyServer proxyServer,//
            boolean forceConnect) {
    //把所有请求信息保证在一个响应回调对象里
    NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, forceConnect);
    //这里视图根据这个请求去拿去channel，过程有点漫长，回头再来解释
    Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);

    if (Channels.isChannelValid(channel))
        return sendRequestWithOpenChannel(request, proxyServer, newFuture, asyncHandler, channel);
    else
        return sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler, performingNextRequest);
}
private Channel getOpenChannel(NettyResponseFuture<?> future, Request request, ProxyServer proxyServer, AsyncHandler<?> asyncHandler) {
    //future并没有channel，对于什么时候channel是可复用的，一直没搞明白，所以我基本默认每次都要新建一个channel
    if (future != null && future.isReuseChannel() && Channels.isChannelValid(future.channel()))
        return future.channel();
    //视图在channelManager中找到可用对象
    else
        return pollPooledChannel(request, proxyServer, asyncHandler);
}

private <T> ListenableFuture<T> sendRequestWithOpenChannel(Request request, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, Channel channel) {
    if (asyncHandler instanceof AsyncHandlerExtensions)
        AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPooled(channel);
    //启动请求超时，在writeRequest中，会启动读取超时
    TimeoutsHolder timeoutsHolder = scheduleRequestTimeout(future);
    timeoutsHolder.initRemoteAddress((InetSocketAddress) channel.remoteAddress());
    future.setChannelState(ChannelState.POOLED);
    future.attachChannel(channel, false);

    Channels.setAttribute(channel, future);
    if (Channels.isChannelValid(channel)) {
        writeRequest(future, channel);
    } else {
        handleUnexpectedClosedChannel(channel, future);
    }
    return future;
}
//把这里当作一个请求连接的开始
private <T> ListenableFuture<T> sendRequestWithNewChannel(//
            Request request,//
            ProxyServer proxy,//
            NettyResponseFuture<T> future,//
            AsyncHandler<T> asyncHandler,//
            boolean performingNextRequest) {

    Realm realm = future.getRealm();
    Realm proxyRealm = future.getProxyRealm();
    //...
    //为做连接做准备
    Bootstrap bootstrap = channelManager.getBootstrap(request.getUri(), proxy);
    //用于索取channel
    Object partitionKey = future.getPartitionKey();
    final boolean acquireChannelLock = !performingNextRequest;

    try {
        //抢占信号量
        if (acquireChannelLock) {
            channelManager.acquireChannelLock(partitionKey);
        }
    } catch (Throwable t) {
        abort(null, future, getCause(t));
        return future;
    }
    //开启请求超时定时器
    scheduleRequestTimeout(future);
    //域名解析
    RequestHostnameResolver.INSTANCE.resolve(request, proxy, asyncHandler)//
            .addListener(new SimpleFutureListener<List<InetSocketAddress>>() {
                @Override
                //域名解析后得到的IP地址列表
                protected void onSuccess(List<InetSocketAddress> addresses) {
                    NettyConnectListener<T> connectListener = new NettyConnectListener<>(future, NettyRequestSender.this, channelManager, acquireChannelLock, partitionKey);
                    //不要怀疑！这里开始连接了！！！
                    NettyChannelConnector connector = new NettyChannelConnector(request.getLocalAddress(), addresses, asyncHandler, clientState, config);
                    if (!future.isDone()) {
                        connector.connect(bootstrap, connectListener);
                    } else if (acquireChannelLock) {
                        //如果future已经完成，则释放信号量
                        channelManager.releaseChannelLock(partitionKey);
                    }
                }
                @Override
                protected void onFailure(Throwable cause) {
                    //失败，释放信号
                    if (acquireChannelLock) {
                        channelManager.releaseChannelLock(partitionKey);
                    }
                    abort(null, future, getCause(cause));
                }
            });
    return future;
}
```

`NettyChannelConnector`负责对远程IP创建连接，一旦连接成功，`NettyConnectListener`就会调用requestSender向服务端发送数据。
``` java
public class NettyChannelConnector {
  public void connect(final Bootstrap bootstrap, final NettyConnectListener<?> connectListener) {
      //获取DNS后的IP地址
      final InetSocketAddress remoteAddress = remoteAddresses.get(i);
      if (asyncHandlerExtensions != null)
          asyncHandlerExtensions.onTcpConnectAttempt(remoteAddress);
      try {
          connect0(bootstrap, connectListener, remoteAddress);
      } catch (RejectedExecutionException e) {
          if (clientState.isClosed()) {
              connectListener.onFailure(null, e);
          }
      }
  }

  private void connect0(Bootstrap bootstrap, final NettyConnectListener<?> connectListener, InetSocketAddress remoteAddress) {
      bootstrap.connect(remoteAddress, localAddress)//
              .addListener(new SimpleChannelFutureListener() {
                  @Override
                  public void onSuccess(Channel channel) {
                      if (asyncHandlerExtensions != null) {
                          asyncHandlerExtensions.onTcpConnectSuccess(remoteAddress, channel);
                      }
                      //如果有设置连接的存活时间，则初始化channelId，在ChannelPool中自检有用到
                      if (connectionTtlEnabled) {
                          Channels.initChannelId(channel);
                      }
                      connectListener.onSuccess(channel, remoteAddress);
                  }
                  @Override
                  public void onFailure(Channel channel, Throwable t) {
                      if (asyncHandlerExtensions != null)
                          asyncHandlerExtensions.onTcpConnectFailure(remoteAddress, t);
                      //如果连接失败，则尝试连接下一个IP
                      boolean retry = pickNextRemoteAddress();
                      if (retry)
                          NettyChannelConnector.this.connect(bootstrap, connectListener);
                      else
                          connectListener.onFailure(channel, t);
                  }
              });
  }
}
```

连接成功，就来到这里，拿到channel，准备向服务器发送数据！
``` java
public final class NettyConnectListener<T> {
  public void onSuccess(Channel channel, InetSocketAddress remoteAddress) {

      Channels.setInactiveToken(channel);
      TimeoutsHolder timeoutsHolder = future.getTimeoutsHolder();
      if (futureIsAlreadyCancelled(channel)) {
          return;
      }
      Request request = future.getTargetRequest();
      Uri uri = request.getUri();
      timeoutsHolder.initRemoteAddress(remoteAddress);

      if (future.getProxyServer() == null && uri.isSecured()) {
        //直接无视
      } else {
          writeRequest(channel);
      }
  }

  private void writeRequest(Channel channel) {
      if (futureIsAlreadyCancelled(channel)) {
          return;
      }
      //在这设置属性，在读取服务器数据的httphandler里面有用到
      Channels.setAttribute(channel, future);
      //注册到ChannelGroup中
      channelManager.registerOpenChannel(channel, partitionKey);
      //设置为不复用channel
      future.attachChannel(channel, false);
      //发送请求数据
      //这个方法就不贴上来了，没什么意思
      //方法里最后将启动读取超时scheduleReadTimeout(future);意味将进入HttpHandler读取服务端数据
      requestSender.writeRequest(future, channel);
  }
}
```
读取数据一切顺利后，就会走下面这个私有方法，将channel送入channelpool里，等待生命的结束！
``` java
public final class HttpHandler extends AsyncHttpClientHandler {
  private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean expectOtherChunks) throws IOException {
      future.cancelTimeouts();
      boolean keepAlive = future.isKeepAlive();
      //这里继续读取后面的数据块，最后channel被设置了回调，依然调用下面的tryToOfferChannelToPool方法
      if (expectOtherChunks && keepAlive)
          channelManager.drainChannelAndOffer(channel, future);
      else
          channelManager.tryToOfferChannelToPool(channel, future.getAsyncHandler(), keepAlive, future.getPartitionKey());
      try {
          future.done();
      } catch (Exception t) {}
  }
}
```

**tryToOfferChannelToPool** 是`ChannelManager`的方法，主要将依然活跃的channel送入生命倒数器中，还记得connectionTtl么，这个参数在这就起作用了！
``` java
public final void tryToOfferChannelToPool(Channel channel, AsyncHandler<?> asyncHandler, boolean keepAlive, Object partitionKey) {
    //长连接，或者依然活跃的
    if (channel.isActive() && keepAlive) {
        //丢弃被设置的属性
        Channels.setDiscard(channel);
        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionOffer(channel);
            //尝试塞进pool里
        if (channelPool.offer(channel, partitionKey)) {
            if (maxConnectionsPerHostEnabled)
                //我没明白这个映射到底是干嘛用的
                channelId2PartitionKey.putIfAbsent(channel, partitionKey);
        } else {
          //被pool驳回，就直接关闭掉！！
            closeChannel(channel);
        }
    } else {
      //已经死亡或者不是长连接，直接关闭！！
        closeChannel(channel);
    }
}
```

<font color=#f28080>到这里，关于channel已经接近尾声了，细心的童鞋可能发现，信号量呢？！不用释放么？！其实在关闭channel的时候，已经释放了，这是因为 **ChannelGroup** 的作用，在将channel注册(add方法)到group的时候，已经在其上面加了关闭的监听器，一旦close就执行remove，实例化 **ChannelGroup** 时已经将`remove(channel)`重写，可以倒回去看是不是已经释放了信号量，也可以看看 **ChannelGroup** 源码是不是在`add`时候添加了监听器。</font>

不过，这里只是接近尾声，没意味就结束了，还有存活的channel被塞到 **ChannelPool** 进行生命的倒计时。
``` java
public final class DefaultChannelPool implements ChannelPool {
  private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<IdleChannel>> partitions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ChannelId, ChannelCreation> channelId2Creation;
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  private final Timer nettyTimer;
  private final int connectionTtl;
  private final boolean connectionTtlEnabled;
  private final int maxIdleTime;
  private final boolean maxIdleTimeEnabled;
  private final long cleanerPeriod;
  private final PoolLeaseStrategy poolLeaseStrategy;

  public DefaultChannelPool(int maxIdleTime,//
            int connectionTtl,//
            PoolLeaseStrategy poolLeaseStrategy,//
            Timer nettyTimer,//
            int cleanerPeriod) {
      this.maxIdleTime = maxIdleTime;
      this.connectionTtl = connectionTtl;
      connectionTtlEnabled = connectionTtl > 0;
      channelId2Creation = connectionTtlEnabled ? new ConcurrentHashMap<>() : null;
      this.nettyTimer = nettyTimer;
      maxIdleTimeEnabled = maxIdleTime > 0;
      this.poolLeaseStrategy = poolLeaseStrategy;
      //在cleanerPeriod清理周期时间、connectionTtl连接存活时间、maxIdleTime最大空闲时间中选择最小的
      this.cleanerPeriod = Math.min(cleanerPeriod定时清理周期, Math.min(connectionTtlEnabled ? connectionTtl : Integer.MAX_VALUE, maxIdleTimeEnabled ? maxIdleTime : Integer.MAX_VALUE));
      //如果开启了连接存活时间，或者最大空闲时间，则实例化空闲channel检测
      if (connectionTtlEnabled || maxIdleTimeEnabled)
          scheduleNewIdleChannelDetector(new IdleChannelDetector());
  }

  private void scheduleNewIdleChannelDetector(TimerTask task) {
      nettyTimer.newTimeout(task, cleanerPeriod, TimeUnit.MILLISECONDS);
  }

  private final class IdleChannelDetector implements TimerTask {
      //挖出已经不满足条件的channel
      private List<IdleChannel> expiredChannels(ConcurrentLinkedDeque<IdleChannel> partition, long now) {
          List<IdleChannel> idleTimeoutChannels = null;
          for (IdleChannel idleChannel : partition) {
              //空闲时间是否过期
              boolean isIdleTimeoutExpired = isIdleTimeoutExpired(idleChannel, now);
              //channel是否还活跃
              boolean isRemotelyClosed = isRemotelyClosed(idleChannel.channel);
              //存活时间是否过期
              boolean isTtlExpired = isTtlExpired(idleChannel.channel, now);
              //满足其中一个条件，加入即将被关闭的channel队列
              if (isIdleTimeoutExpired || isRemotelyClosed || isTtlExpired) {
                  if (idleTimeoutChannels == null)
                      idleTimeoutChannels = new ArrayList<>(1);
                  idleTimeoutChannels.add(idleChannel);
              }
          }
          return idleTimeoutChannels != null ? idleTimeoutChannels : Collections.<IdleChannel> emptyList();
      }
      //关闭expiredChannels筛选出来的队列，并返回一个已被close的channel队列
      private List<IdleChannel> closeChannels(List<IdleChannel> candidates) {
          List<IdleChannel> closedChannels = null;
          for (int i = 0; i < candidates.size(); i++) {
              IdleChannel idleChannel = candidates.get(i);
              //如果未被占有，则直接close；如果中间出现有被占有的channel，实例化closedChannels，并将之前被close的channel塞进其中
              if (idleChannel.takeOwnership()) {
                  close(idleChannel.channel);
                  if (closedChannels != null) {
                      closedChannels.add(idleChannel);
                  }
              } //注意，这里只会被执行一次，closedChannels被实例化后不会再执行
              else if (closedChannels == null) {
                  closedChannels = new ArrayList<>(candidates.size());
                  for (int j = 0; j < i; j++)
                      closedChannels.add(candidates.get(j));
              }
          }
          //如果closedChannels为null，代表已经关闭candidates所有channel，原封不动返回
          //如果closedChannels非null，代表被占用的channel没有close并继续存活在candidates，所以返回被close了的channel队列closedChannels
          return closedChannels != null ? closedChannels : candidates;
      }
      public void run(Timeout timeout) throws Exception {
          if (isClosed.get())
              return;
          //检测器的启动时间
          long start = unpreciseMillisTime();
          int closedCount = 0;
          int totalCount = 0;
          //遍历每个路由的被塞到ChannelPool的channel队列
          for (ConcurrentLinkedDeque<IdleChannel> partition : partitions.values()) {
              List<IdleChannel> closedChannels = closeChannels(expiredChannels(partition, start));
              //非空且开启了连接存活时间的channel且被close的channel，全部从channelId2Creation和partition中去除
              if (!closedChannels.isEmpty()) {
                  if (connectionTtlEnabled) {
                      for (IdleChannel closedChannel : closedChannels)
                          channelId2Creation.remove(channelId(closedChannel.channel));
                  }
                  partition.removeAll(closedChannels);
                  closedCount += closedChannels.size();
              }
          }
          //退出并继续下一轮检测
          scheduleNewIdleChannelDetector(timeout.task());
      }
  }

  //存放空闲channel
  private static final class IdleChannel {
      final Channel channel;
      final long start;
      final AtomicBoolean owned = new AtomicBoolean(false);
      IdleChannel(Channel channel, long start) {
          this.channel = assertNotNull(channel, "channel");
          this.start = start;
      }
      public boolean takeOwnership() {
          return owned.compareAndSet(false, true);
      }
      @Override
      public boolean equals(Object o) {...}
      @Override
      public int hashCode() {...}
  }
  //存放channel的创建时间
  private static final class ChannelCreation {
      final long creationTime;
      final Object partitionKey;
      ChannelCreation(long creationTime, Object partitionKey) {
          this.creationTime = creationTime;
          this.partitionKey = partitionKey;
      }
  }
}
```
这里才是channel的终结！！！

channel被终结了，但有些还存活的channel还在请求的路上，还有很重要的两点没说到，就是 **请求超时** 和 **读取超时**。
每个`NettyResponseFuture`都持有一个`TimeoutsHolder`来计算 **requestTimeout** 和 **readTimeout** 是否过期。在ResponseFuture获取连接后，以及获取成功向服务器发送数据后，都会分别启动请求超时和读取超时两个定时器。通过阅读源码，可以发现 **requestTimeout** 其实是包括了 **readTimeout**，如果请求剩余时间小于读取超时时间时，`startReadTimeout`是不会启动readTimeout定时器的。下面只贴上`TimeoutsHolder`的部分源码，`RequestTimeoutTimerTask`和`ReadTimeoutTimerTask`可以自行阅读。

<font color=#f28080>对于这两个参数，需要说明一点就是，一旦超时过期，channel和future都会被close掉，如果读超设置比请超长则是无意义的，只会以requestTimeout为准。</font>
``` java
public class TimeoutsHolder {
  private final AtomicBoolean cancelled = new AtomicBoolean();

  private final Timer nettyTimer;
  private final NettyRequestSender requestSender;
  private final long requestTimeoutMillisTime;
  private final int readTimeoutValue;

  private volatile NettyResponseFuture<?> nettyResponseFuture;
  public final Timeout requestTimeout;
  public volatile Timeout readTimeout;

  public TimeoutsHolder(Timer nettyTimer, NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, AsyncHttpClientConfig config) {
      this.nettyTimer = nettyTimer;
      this.nettyResponseFuture = nettyResponseFuture;
      this.requestSender = requestSender;
      this.readTimeoutValue = config.getReadTimeout();
      int requestTimeoutInMs = nettyResponseFuture.getTargetRequest().getRequestTimeout();
      //每个请求都可以独立设置请求超时时间
      if (requestTimeoutInMs == 0) {
          requestTimeoutInMs = config.getRequestTimeout();
      }
      if (requestTimeoutInMs != -1) {
          //请求的到期时间，启动请求超时定时器
          requestTimeoutMillisTime = unpreciseMillisTime() + requestTimeoutInMs;
          requestTimeout = newTimeout(new RequestTimeoutTimerTask(nettyResponseFuture, requestSender, this, requestTimeoutInMs), requestTimeoutInMs);
      } else {
          requestTimeoutMillisTime = -1L;
          requestTimeout = null;
      }
  }

  public void startReadTimeout() {
      if (readTimeoutValue != -1) {
          startReadTimeout(null);
      }
  }
  void startReadTimeout(ReadTimeoutTimerTask task) {
      //如果requestTimeout不为null，或者requestTimeout还没有过期并且读取超时时间<请求剩余时间
      if (requestTimeout == null || (!requestTimeout.isExpired() && readTimeoutValue < (requestTimeoutMillisTime - unpreciseMillisTime()))) {
          if (task == null) {
              task = new ReadTimeoutTimerTask(nettyResponseFuture, requestSender, this, readTimeoutValue);
          }
          Timeout readTimeout = newTimeout(task, readTimeoutValue);
          this.readTimeout = readTimeout;
      } else if (task != null) {
          task.clean();
      }
  }
}
```

最后最后最后。。。

来总结一下ahc的连接池实现，很明显的一点整个过程都是对`Channel`的管理，而且对于连接的抢占则使用了`Semaphore`，这再方便不过了！！！对于信号量的释放，Netty的`ChannelGroup`有很大的功劳，它提供了最优雅的方式关闭channel并且释放信号量。除此之外，一堆的超时限制任务需要一个定时任务容器执行，Netty又提供了一个在面对大量任务依然稳坐泰山的`HashedWheelTimer`，有机会专门来说说这一个。还有就是`DefaultChannelPool`对存活时间的检测，实在是通俗易懂，而且基于前面说的几点，实现起来也相当方便。

如果遇到基于netty的网络编程开发，对于连接资源的管理ahc确实提供了一套不错的思路，不仅对客户端，服务端也是可以试一试的！
