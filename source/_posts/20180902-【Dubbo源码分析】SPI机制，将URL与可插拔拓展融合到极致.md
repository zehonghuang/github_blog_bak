---
title: 20180902 -【Dubbo源码分析】SPI机制，将URL与可插拔拓展融合到极致
date: 2018-09-02 16:01:41
tags:
  - java　
  - Dubbo
  - SPI
  - 反射
  - rpc
categories:
  - rpc框架
---

开始啃Dubbo时候，对其功能多样被吓到，若干功能由xml配置可任意组装，担心其复杂度过高，源码难啃。当我开始啃启动步骤的代码后，发现`ExtensionLoader`经常出现在较重要实例化的地方。
是的，`ExtensionLoader、@SPI、@Adaptive、@Activate`可以说是Dubbo的核心，`Cluster、Protocol、Filter`等接口都被声明为SPI，什么作用呢？能根据配置动态调用被声明为SPI接口的实现类，dubbo提供了URL方式作为参数配置。

---

``` java
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
/**
 * SPI("默认值")
 */
@SPI
public interface TestAdaptiveExt {
  /*
   * Adaptive({参数名数组})
   */
  @Adaptive({"key_name_1", "key_name_2"})
  String getChlidInfo(URL url);
}

public class TestAdaptiveExtImpl1 implements TestAdaptiveExt {
  public String getChlidInfo(URL url) {
    return "test1";
  }
}
public class TestAdaptiveExtImpl2 implements TestAdaptiveExt {
  public String getChlidInfo(URL url) {
    return "test2";
  }
}

```

``` java
@Test
public void testAdaptive() {
  ExtensionLoader<TestAdaptiveExt> loader = ExtensionLoader.getExtensionLoader(TestAdaptiveExt.class);
  TestAdaptiveExt testAdaptiveExt = loader.getAdaptiveExtension();
  /**
   *
   */
  URL url = URL.valueOf("test://localhost/test?key_name_1=default_2");
  System.out.println(testAdaptiveExt.getChlidInfo(url));
}
```
resources/META-INF/dubbo/internal/com.hongframe.extension.TestAdaptiveExt

``` properties
default_1=com.hongframe.extension.impl.TestAdaptiveExtImpl1
default_2=com.hongframe.extension.impl.TestAdaptiveExtImpl2
```
