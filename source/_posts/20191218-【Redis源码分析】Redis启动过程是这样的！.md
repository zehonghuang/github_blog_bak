---
title: 【Redis源码分析】Redis启动过程是这样的！
date: 2019-12-16 11:23:29
tags:
    - C/C++
    - redis
    - Linux内核
    - Linux编程
categories:
    - C/C++编程
---

redis的源码确实是比我想象中要好读，本身是过程式编程，所以很多核心逻辑已经包含在main函数的调用链，流程非常长，耐心看下去还是能get到些东西的。

---

main函数包含了很多逻辑：哨兵模式、定时任务、持久化、模块加载、从磁盘加载数据等等，每个地方都能单独拎出来讲，这里就不说太多了

``` c++
int main(int argc, char **argv) {
    struct timeval tv;
    int j;

    /* We need to initialize our libraries, and the server configuration. */
#ifdef INIT_SETPROCTITLE_REPLACEMENT
    spt_init(argc, argv);
#endif
    //设置地域，对字符集有影响
    setlocale(LC_COLLATE,"");
    tzset(); //时区设置
    //写异常报告
    zmalloc_set_oom_handler(redisOutOfMemoryHandler);
    srand(time(NULL)^getpid());
    gettimeofday(&tv,NULL);

    char hashseed[16];
    //从/dev/urandom文件获取随机数生成dict所需的随机种子
    //urandom记录系统混乱值，包括软硬件信息
    getRandomHexChars(hashseed,sizeof(hashseed));
    dictSetHashFunctionSeed((uint8_t*)hashseed);
    server.sentinel_mode = checkForSentinelMode(argc,argv);
    initServerConfig();
    moduleInitModulesSystem();

    /* Store the executable path and arguments in a safe place in order
     * to be able to restart the server later. */
    server.executable = getAbsolutePath(argv[0]);
    server.exec_argv = zmalloc(sizeof(char*)*(argc+1));
    server.exec_argv[argc] = NULL;
    for (j = 0; j < argc; j++) server.exec_argv[j] = zstrdup(argv[j]);

    /* We need to init sentinel right now as parsing the configuration file
     * in sentinel mode will have the effect of populating the sentinel
     * data structures with master nodes to monitor. */
    if (server.sentinel_mode) {
        initSentinelConfig();
        initSentinel();
    }

    /* Check if we need to start in redis-check-rdb/aof mode. We just execute
     * the program main. However the program is part of the Redis executable
     * so that we can easily execute an RDB check on loading errors. */
    if (strstr(argv[0],"redis-check-rdb") != NULL)
        redis_check_rdb_main(argc,argv,NULL);
    else if (strstr(argv[0],"redis-check-aof") != NULL)
        redis_check_aof_main(argc,argv);

    /*
     * 这里有一大段参数处理，被我去掉
     */

    // 管理守护线程的方式
    // service redis restart
    // systemctl start redis
    // 直接kill -9，redis会被重新拉起，以防误杀
    server.supervised = redisIsSupervised(server.supervised_mode);
    int background = server.daemonize && !server.supervised; // 后台运行
    if (background) daemonize(); // 调用setsid()，父子进程脱离

    // 初始化socket、eventloop、定时器: 持久化任务、超时任务
    // 初始化持久化进程通讯用的通道
    initServer();
    if (background || server.pidfile) createPidFile();
    redisSetProcTitle(argv[0]);
    redisAsciiArt(); // 打印LOGO
    checkTcpBacklogSettings();

    if (!server.sentinel_mode) {
        /* Things not needed when running in Sentinel mode. */
        serverLog(LL_WARNING,"Server initialized");
    #ifdef __linux__
        linuxMemoryWarnings();
    #endif
        moduleLoadFromQueue(); // 加载module
        InitServerLast();
        loadDataFromDisk();
        if (server.cluster_enabled) {
            if (verifyClusterConfigWithData() == C_ERR) {
                serverLog(LL_WARNING,
                    "You can't have keys in a DB different than DB 0 when in "
                    "Cluster mode. Exiting.");
                exit(1);
            }
        }
    } else {
        InitServerLast();
        sentinelIsRunning();
    }

    /* Warning the user about suspicious maxmemory setting. */
    if (server.maxmemory > 0 && server.maxmemory < 1024*1024) {
        serverLog(LL_WARNING,"WARNING: You specified a maxmemory value that is less than 1MB (current value is %llu bytes). Are you sure this is what you really want?", server.maxmemory);
    }

    // 依次做了
    // 1、集群模式下，更新节点状态
    // 2、清理过期Key-Value
    // 3、向从库同步数据
    // 4、主从同步时，会锁住客户端请求，并在这里释放锁
    // 5、尝试执行被刮起的客户端命令
    // 6、持久化AOF
    // 7、处理被阻塞的向客户端写操作
    // 8、释放拓展模块的全局锁（在加载conf以配置的module时加了独自锁）
    aeSetBeforeSleepProc(server.el,beforeSleep);
    // 获取拓展模块的全局锁
    aeSetAfterSleepProc(server.el,afterSleep);
    aeMain(server.el);  // 这是正式启动轮训，单线程处理请求
    aeDeleteEventLoop(server.el);
    return 0;
}
```

`initServer`初始化了一堆文件or时间监听的事件，其中任务较重的应该是`serverCron`了，有机会再细说。
``` cpp
void initServer(void) {
    int j;

    signal(SIGHUP, SIG_IGN); // 忽略终端会话结束
    signal(SIGPIPE, SIG_IGN); // 若不忽略信号会出现Broken pipe，且退出进程
    setupSignalHandlers();

    if (server.syslog_enabled) {
        openlog(server.syslog_ident, LOG_PID | LOG_NDELAY | LOG_NOWAIT,
            server.syslog_facility);
    }

    createSharedObjects();
    adjustOpenFilesLimit();
    server.el = aeCreateEventLoop(server.maxclients+CONFIG_FDSET_INCR);
    if (server.el == NULL) {
        //err log
        exit(1);
    }
    server.db = zmalloc(sizeof(redisDb)*server.dbnum);

    /* Open the TCP listening socket for the user commands. */
    if (server.port != 0 &&
        listenToPort(server.port,server.ipfd,&server.ipfd_count) == C_ERR)
        exit(1);

    // 本地进程通信，用于安全访问
    /* Open the listening Unix domain socket. */
    if (server.unixsocket != NULL) {
        unlink(server.unixsocket); /* don't care if this fails */
        server.sofd = anetUnixServer(server.neterr,server.unixsocket,
            server.unixsocketperm, server.tcp_backlog);
        if (server.sofd == ANET_ERR) {
            serverLog(LL_WARNING, "Opening Unix socket: %s", server.neterr);
            exit(1);
        }
        anetNonBlock(NULL,server.sofd);
    }

    /* Abort if there are no listening sockets at all. */
    if (server.ipfd_count == 0 && server.sofd < 0) {
        // exit
    }

    for (j = 0; j < server.dbnum; j++) {
        //配置server.db属性，默认16个
    }
    
    /*
     * 省略一堆server设置
     */

    // 处理客户端超时、无法访问的超时key、定时定量持久化
    if (aeCreateTimeEvent(server.el, 1, serverCron, NULL, NULL) == AE_ERR) {
        // exit
    }

    /* Create an event handler for accepting new connections in TCP and Unix
     * domain sockets. */
    for (j = 0; j < server.ipfd_count; j++) {
        if (aeCreateFileEvent(server.el, server.ipfd[j], AE_READABLE,
            acceptTcpHandler,NULL) == AE_ERR)
            {
                serverPanic(
                    "Unrecoverable error creating server.ipfd file event.");
            }
    }
    if (server.sofd > 0 && aeCreateFileEvent(server.el,server.sofd,AE_READABLE,
        acceptUnixHandler,NULL) == AE_ERR) serverPanic("Unrecoverable error creating server.sofd file event.");


    // 若被阻塞的客户端需要时可唤醒轮训，在beforeSleep尝试执行完客户端请求，moduleBlockedClientPipeReadable为空方法
    if (aeCreateFileEvent(server.el, server.module_blocked_pipe[0], AE_READABLE,
        moduleBlockedClientPipeReadable,NULL) == AE_ERR) {
            serverPanic(
                "Error registering the readable event for the module "
                "blocked clients subsystem.");
    }

    /* Open the AOF file if needed. */
    if (server.aof_state == AOF_ON) {
        server.aof_fd = open(server.aof_filename,
                               O_WRONLY|O_APPEND|O_CREAT,0644);
        if (server.aof_fd == -1) {
            // exit
        }
    }

    // 考虑32为系统地址空间仅为4G，所以在没有指定maxmemory下限制3G，以为内存不足而无法工作
    if (server.arch_bits == 32 && server.maxmemory == 0) {
        serverLog(LL_WARNING,"Warning: 32 bit instance detected but no memory limit set. Setting 3 GB maxmemory limit with 'noeviction' policy now.");
        server.maxmemory = 3072LL*(1024*1024); /* 3 GB */
        server.maxmemory_policy = MAXMEMORY_NO_EVICTION;
    }

    if (server.cluster_enabled) clusterInit();
    replicationScriptCacheInit();
    scriptingInit(1);
    slowlogInit();
    latencyMonitorInit();
}
```