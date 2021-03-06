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

其实SPI的应用跟实现逻辑是相当简单的，但很巧妙，正文的源码分析部分只罗列出主要方法，以及简单说明其作用。

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
   * 此处，对于Activate，URI的key=value只能被key激活，但对相同key的Adaptive方法能达到组合效果
   * 参考CacheFilter与CacheFactory的用法
   */
  /*
   * 这里有个值得注意的地方：
   * 如果给url添加key_name_1=<-default,...,...>作为参数的话，是不匹配Activate.value的，转而匹配配置文件中的key，例如active1，active2
   */
  url = url.addParameter("key_name_1", "default_1");
  List<TestActivateExt> testActivateExts = testActivateExtExtensionLoader.getActivateExtension(url, "key_name_1");
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

下面是加载拓展点实现类的代码，主要通过META-INF里的配置文件找到相应的类。
``` java
private Map<String, Class<?>> getExtensionClasses() {
  //省略单例双检代码
  //
  classes = loadExtensionClasses();
  cachedClasses.set(classes);
  return classes;
}

// synchronized in getExtensionClasses
private Map<String, Class<?>> loadExtensionClasses() {
  final SPI defaultAnnotation = type.getAnnotation(SPI.class);
  if (defaultAnnotation != null) {
    String value = defaultAnnotation.value();
    if ((value = value.trim()).length() > 0) {
      //至多一个默认拓展实现类名
      String[] names = NAME_SEPARATOR.split(value);
      if (names.length > 1) {
        //IllegalStateException
      }
      if (names.length == 1) cachedDefaultName = names[0];
    }
  }

  Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
  //load配置文件
  //其中loadResource是很简单的词法解析器，loadClass则负责将实现类缓存在成员变量
  loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);//"META-INF/dubbo/internal/"
  loadDirectory(extensionClasses, DUBBO_DIRECTORY);//"META-INF/dubbo/"
  loadDirectory(extensionClasses, SERVICES_DIRECTORY);//"META-INF/services/"
  return extensionClasses;
}

private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL,//抛异常用的，没什么实际用处
Class<?> clazz,//配置文件中的value
//配置文件中的key
String name) throws NoSuchMethodException {
  //是否拓展点type的实现类
  if (!type.isAssignableFrom(clazz)) {
    //IllegalStateException
  }
  //该拓展点实现类是否被声明为Adaptive
  if (clazz.isAnnotationPresent(Adaptive.class)) {
    if (cachedAdaptiveClass == null) {
      cachedAdaptiveClass = clazz;
    } else if (!cachedAdaptiveClass.equals(clazz)) {
      //IllegalStateException，只能声明一个实现类
    }
    //是否为包装类
  } else if (isWrapperClass(clazz)) {
    Set<Class<?>> wrappers = cachedWrapperClasses;
    if (wrappers == null) {
      cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
      wrappers = cachedWrapperClasses;
    }
    wrappers.add(clazz);
  } else {
    //这一步是加载被声明为Activate的实现累的
    clazz.getConstructor();
    if (name == null || name.length() == 0) {
      //其中涉及到一个过期的注解@Extension
      //如果配置文件没有设置key值，将实现类simplename作为默认
      name = findAnnotationName(clazz);
      if (name.length() == 0) {
        //IllegalStateException
      }
    }
    String[] names = NAME_SEPARATOR.split(name);
    if (names != null && names.length > 0) {
      Activate activate = clazz.getAnnotation(Activate.class);
      if (activate != null) {
        cachedActivates.put(names[0], activate);
      }
      for (String n : names) {
        if (!cachedNames.containsKey(clazz)) {
          cachedNames.put(clazz, n);
        }
        Class<?> c = extensionClasses.get(n);
        if (c == null) {
          //把不同key相同class全都塞进去
          extensionClasses.put(n, clazz);
        } else if (c != clazz) {
          //IllegalStateException
        }
      }
    }
  }
}
```

下面是获取Activate拓展实现的列表方法，通常这类拓展都是需要AOP来处理事情的，类似Filter接口。

``` java
public List<T> getActivateExtension(URL url,
//url的param-key列表
String[] values, String group) {
  List<T> exts = new ArrayList<T>();
  List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
  //这部分从url中拿去参数，匹配符合条件的拓展点
  //如果key=value，后者出现-default，则表示不走匹配URL的方式
  if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
    getExtensionClasses();
    for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
      String name = entry.getKey();
      Activate activate = entry.getValue();
      if (isMatchGroup(group, activate.group())) {
        T ext = getExtension(name);
        if (!names.contains(name)
              && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
              && isActive(activate, url)) {
          exts.add(ext);
        }
      }
    }
    //根据before、after、order优先级依次排序
    Collections.sort(exts, ActivateComparator.COMPARATOR);
  }
  //遍历values给到的参数
  List<T> usrs = new ArrayList<T>();
  for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      //跳过被标记-的拓展点
      if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
              && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
          if (Constants.DEFAULT_KEY.equals(name)) {
              if (!usrs.isEmpty()) {
                exts.addAll(0, usrs);
                usrs.clear();
              }
          } else {
            //注意，这里的name是配置文件中的key
            T ext = getExtension(name);
            usrs.add(ext);
          }
      }
  }
  if (!usrs.isEmpty()) {
    exts.addAll(usrs);
  }
  return exts;
}
```

- 总结

其实没多少可以总结的，dubbo的微内核其实就一个ExtensionLoader类，但弄清之后，可以较为清晰的研究dubbo各种功能的源码，因为其功能也是拓展点之一，只要找到对于实现即可。
