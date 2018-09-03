---
title: 【Dubbo源码分析】SPI机制，将URL与可插拔拓展融合到极致
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

- 正题，源码分析

Dubbo为每个拓展点准备一个ExtensionLoader，将DUBBO_INTERNAL_DIRECTORY、SERVICES_DIRECTORY下的配置文件全部缓存到以下成员变量中。


``` java
public class ExtensionLoader<T> {
  //一个拓展点一个ExtensionLoader
  private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();
  //拓展点实现类的实例化
  private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

  // ==============================
  //拓展点
  private final Class<?> type;
  private final ExtensionFactory objectFactory;
  //缓存拓展点实现的名称
  private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();
  //缓存拓展点的实现类
  private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();
  //实现类的Activate注解配置
  private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
  private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
  //至关重要的，通过代码注入后得到实例化
  private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
  //拓展点实现类被声明为Adaptive，至多一个
  private volatile Class<?> cachedAdaptiveClass = null;
  //SPI("cachedDefaultName") 默认实现类key值
  private String cachedDefaultName;
  private volatile Throwable createAdaptiveInstanceError;
  //拓展点包装类
  private Set<Class<?>> cachedWrapperClasses;

  public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
    if (type == null)
      //非空
    if (!type.isInterface()) {
      //必须是接口
    }
    if (!withExtensionAnnotation(type)) {
        //必须声明@SPI
    }
    ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
    if (loader == null) {
        EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
        loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
    }
    return loader;
  }
}
```

`getAdaptiveExtension`获取拓展点实例化对象，这里会动态实例化一个拓展点实现，将其set入Holder，这个对象包装了拓展点基础的实现类，成为一个动态代理。

``` java
public T getAdaptiveExtension() {
  Object instance = cachedAdaptiveInstance.get();
  if (instance == null) {
    if (createAdaptiveInstanceError == null) {
      synchronized (cachedAdaptiveInstance) {
        instance = cachedAdaptiveInstance.get();
        if (instance == null) {
          try {
            //创建Adaptive拓展点接口的实例对象
            instance = createAdaptiveExtension();
            cachedAdaptiveInstance.set(instance);
          } catch (Throwable t) {
            createAdaptiveInstanceError = t;
            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
          }
        }
      }
    } else {
      //IllegalStateException
    }
  }
  return (T) instance;
}

private T createAdaptiveExtension() {
  try {
    //向拓展点的成员变量注入对象
    return injectExtension(
        //加载，动态生成code，编译，实例化
        (T) getAdaptiveExtensionClass().newInstance());
  } catch (Exception e) {
    throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
  }
}
private Class<?> getAdaptiveExtensionClass() {
  //加载成员变量type指定的配置文件
  getExtensionClasses();
  if (cachedAdaptiveClass != null) {
    return cachedAdaptiveClass;
  }
  //初始化：生成拓展点实现类代码，以TestAdaptiveExt为例
  return cachedAdaptiveClass = createAdaptiveExtensionClass();
}
```

`createAdaptiveExtensionClass()`方法生成的TestAdaptiveExt实现类，详细逻辑自行阅读`createAdaptiveExtensionClassCode()`。
``` java
package com.hongframe.extension;

import com.alibaba.dubbo.common.extension.ExtensionLoader;

public class TestAdaptiveExt$Adaptive implements com.hongframe.extension.TestAdaptiveExt {

public java.lang.String getChlidInfo(com.alibaba.dubbo.common.URL arg0) {

	if (arg0 == null) throw new IllegalArgumentException("url == null");
	com.alibaba.dubbo.common.URL url = arg0;
	String extName = url.getParameter("key_name_1", url.getParameter("key_name_2"));
	if(extName == null) throw new IllegalStateException("Fail to get extension(com.hongframe.extension.TestAdaptiveExt) name from url(" + url.toString() + ") use keys([key_name_1, key_name_2])");
	com.hongframe.extension.TestAdaptiveExt extension =
					(com.hongframe.extension.TestAdaptiveExt)ExtensionLoader
								.getExtensionLoader(com.hongframe.extension.TestAdaptiveExt.class).getExtension(extName);
	return extension.getChlidInfo(arg0);
	}
}
```
