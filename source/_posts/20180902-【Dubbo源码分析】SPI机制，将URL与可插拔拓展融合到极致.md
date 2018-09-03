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
- 先来看@SPI

每个要成为拓展点的接口都需要被声明SPI，ExtensionLoader只加载有该注解的接口，SPI可以设置一个默认值，指向META-INF中拓展点文件的key值，如果你在url所配的参数找不到会走默认。

- @Adaptive

该注解能作用在类型与方法：作用于方法，默认URL作为匹配方法；作用于类型，可自定义匹配方式，不一定要URL。


``` java
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
/**
 * SPI("默认值")
 */
@SPI("default_1")
public interface TestAdaptiveExt {
  /*
   * Adaptive({参数名数组})
   */
  @Adaptive({"key_name_1"})
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

@Test
public void testAdaptive() {
  ExtensionLoader<TestAdaptiveExt> loader = ExtensionLoader.getExtensionLoader(TestAdaptiveExt.class);
  TestAdaptiveExt testAdaptiveExt = loader.getAdaptiveExtension();
  /**
   * 控制台输出：test2
   */
  URL url = URL.valueOf("test://localhost/test?Adaptive_key_name=default_2");
  System.out.println(testAdaptiveExt.getChlidInfo(url));
}
```
resources/META-INF/dubbo/internal/com.hongframe.extension.TestAdaptiveExt

``` properties
default_1=com.hongframe.extension.impl.TestAdaptiveExtImpl1
default_2=com.hongframe.extension.impl.TestAdaptiveExtImpl2
```

- @Activate

Activate提供了`group、value、before、after、order`，除前两个用来筛选外，其余三个均排序，该注解只能作用于类型，方法无效。

``` java
@SPI
public interface TestActivateExt {
  String print();
}

@Activate(value = "key_name_1", group = "xxx_group")
public class TestActivateExtImpl1 implements TestActivateExt {
  public String print() {
    return "TestActivateExtImpl1";
  }
}

@Activate("key_name_2")
public class TestActivateExtImpl2 implements TestActivateExt {
  public String print() {
    return "TestActivateExtImpl2";
  }
}

@Test
public void testActivate() {
  ExtensionLoader<TestActivateExt> testActivateExtExtensionLoader = ExtensionLoader.getExtensionLoader(TestActivateExt.class);
  URL url = URL.valueOf("test://localhost/test");
  /*
   * 对于Activate，URI的key=value只能被key激活，但对相同key的Adaptive方法能达到组合效果
   * 参考CacheFilter与CacheFactory的用法
   */
  url = url.addParameter("key_name_1", "default_1");
  List<TestActivateExt> testActivateExts = testActivateExtExtensionLoader.getActivateExtension(url, new String[]{});
  for(TestActivateExt ext : testActivateExts)
   System.out.println(ext.print());
}
```

resources/META-INF/dubbo/internal/com.hongframe.extension.TestActivateExt
``` properties
active1=com.hongframe.extension.impl.TestActivateExtImpl1
active2=com.hongframe.extension.impl.TestActivateExtImpl2
```
