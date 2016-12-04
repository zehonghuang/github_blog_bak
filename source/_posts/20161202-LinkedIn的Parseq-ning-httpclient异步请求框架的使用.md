---
title: LinkedIn的Parseq + ning.httpclient异步请求框架的使用
date: 2016-12-02 19:44:58
categories:
- 开源框架
tags:
- java
- 异步框架
- parseq
---

Parseq是Linkedin的一个异步框架，目前来说是个封装的较好而且易用的异步框架。除了普通的数据处理外，还支持网络请求、消息队列等。

---

## 1. Maven配置
``` xml
<dependency>
    <groupId>com.linkedin.parseq</groupId>
    <artifactId>parseq</artifactId>
    <version>2.6.3</version>
</dependency>
<dependency>
    <groupId>com.linkedin.parseq</groupId>
    <artifactId>parseq-http-client</artifactId>
    <version>2.6.3</version>
</dependency>
```

## 2. 创建以及关闭线程池引擎Engine

``` java
private static ExecutorService taskService;
private static ScheduledExecutorService timerService;
private static Engine engine;
private static JsonMapper mapper = new JsonMapper();

static {
    int numCores = Runtime.getRuntime().availableProcessors();
    //可伸缩的线程池
    taskService = new ThreadPoolExecutor(numCores, numCores * 2, 30, TimeUnit.SECONDS,
    new ArrayBlockingQueue<Runnable>(100), new CallerRunsPolicy());

    timerService = Executors.newScheduledThreadPool(numCores);
    engine = new EngineBuilder().setTaskExecutor(taskService).setTimerScheduler(timerService).build();
}

public static void shutdown() {
    try {
        if (engine != null) {
            log.info("shutdown engine");
                engine.shutdown();
                    engine.awaitTermination(3, TimeUnit.SECONDS);
        }
        if (taskService != null) {
            log.info("shutdown taskService");
            taskService.shutdown();
        }
        if (timerService != null) {
            log.info("shutdown timerService");
            timerService.shutdown();
        }
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
}
```
## 3. 创建简单请求任务Task
``` java
  /**
	 * 创建Post任务
	 *
	 * @param taskName
	 *            任务名称
	 * @param url
	 *            目标链接
	 * @param params
	 *            查询参数
	 * @param headers
	 *            报头
	 * @param body
	 *            报文
	 * @return
	 */
public static Task<String> createPostTask(String taskName, String url, List<Param> params, Map<String, String> headers, String body) {
                                    //设置重试机制
    Task<String> reusableTask = Task.withRetryPolicy(createRetryPolicy(), () -> {
        //final WrappedRequestBuilder builder = HttpClient.get(url); //Get请求
        final WrappedRequestBuilder builder = HttpClient.post(url);
        if (body != null)
            builder.setBody(body);
        if (headers != null)
            headers.entrySet().forEach(entry -> builder.addHeader(entry.getKey(), entry.getValue()));
        if (params != null)
            builder.addQueryParams(params);
        return builder.task().map(taskName, Response::getResponseBody)
                //超时会抛出java.util.concurrent.TimeoutException
                .withTimeout(5, TimeUnit.SECONDS);
    })
    //错误处理，出现异常错误，则默认输出
    .recover(e -> "{\"success:\":false}");
    return reusableTask;
}
```

## 4.合并简单任务，以及任务结果处理

回调接口，用以合并处理请求结果

``` java
public interface TaskResultHandler {
                        //SpringMvc异步请求
    default void setResult(DeferredResult<GwResult> deferredResult, String result) {
        deferredResult.setResult(result);
    }
}
//函数式接口注释
@FunctionalInterface
public interface TaskResultHandler1 extends TaskResultHandler {
    String handle(String result);
}
@FunctionalInterface
public interface TaskResultHandler2 extends TaskResultHandler {
    String handle(String result1, String result2);
}

// ..................n个TaskResultHandler.................
```

任务的合并

``` java
       /**
	 * 合并任务
	 *
	 * @param taskName
	 *            任务名称
	 * @param task1
	 * @param task2
	 * @param handler
	 *            任务结果处理
	 * @return
	 */
public static Task<String> merge(String taskName, Task<String> task1, Task<String> task2, TaskResultHandler2 handler) {
    return Task.par(task1, task2)
        //合并处理两个任务结果
        .map(taskName, (result1, result2) -> handler.handle(result1, result2));
}

public static Task<String> merge(String taskName, Task<String> task1, Task<String> task2, Task<String> task3,
			TaskResultHandler3 handler) {
    return Task.par(task1, task2, task3)
         //合并处理三个任务结果
        .map(taskName,(result1, result2, result3) -> handler.handle(result1, result2, result3));
}

// ..................n个merge方法.................
```

任务开跑！

``` java
public static void run(Task<String> task, TaskResultHandler1 handler, DeferredResult<GwResult> deferredResult) {
    engine.run(task.map("runTask1", (result) -> handler.handle(result))
            .andThen(result -> {
                if (deferredResult != null)
                    handler.setResult(deferredResult, result);
                else
                    log.info(result);
             }).recover(e -> {
                    //输出系统错误结果
                    deferredResult.setResult(gwResult);
                    return mapper.toJson(gwResult);
            }));
}

public static void run(Task<String> task1, Task<String> task2, TaskResultHandler2 handler,
			DeferredResult<GwResult> deferredResult) {
    Task<String> tasks = merge("runTask2", task1, task2, handler)
        //setResultElsePrintLog与上面的方法相同
        .andThen(setResultElsePrintLog(handler, deferredResult))
        ////recoverHandle与上面的方法相同
        .recover(recoverHandle(deferredResult));
    engine.run(tasks);
}

// ..................n个run方法.................
```

## 5.异常处理以及重试机制

``` java
private static RetryPolicy createRetryPolicy() {
    return new RetryPolicyBuilder().setTerminationPolicy(TerminationPolicy.limitAttempts(3))
        // .setErrorClassifier(null)
        .build();
}
```
