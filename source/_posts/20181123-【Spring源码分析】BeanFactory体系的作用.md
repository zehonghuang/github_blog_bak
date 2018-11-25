---
title: 20181123-【Spring源码分析】BeanFactory体系的作用
date: 2018-11-23 11:54:47
tags:
  - java　
  - Spring
  - AOP
  - IOC控制反转
  - BeanFactory
categories:
    - Spring源码分析
---

最近静下心开始研读spring源码，从容器开始，直入眼帘的就是BeanFactory这个终极boss。通过BeanFactory及其子接口能得知不同的注入方式与获取方式，在尝试拓展自己的实例化时，有必要的用处。

---

先看看`BeanFactory`的最终实现类`DefaultListableBeanFactory`，生成出来的类关系图，看起来相当的复杂。本文暂且只讨论`BeanFactory`本身，与一代子接口`AutowireCapableBeanFactory`、`HierarchicalBeanFactory`、`ListableBeanFactory`，还有个`ConfigurableBeanFactory`实在不想展开讲，东西有点多。

![DefaultListableBeanFactory类图](https://raw.githubusercontent.com/zehonghuang/github_blog_bak/master/source/image/DefaultListableBeanFactory.png)


- BeanFactory顶级父类

作为顶级接口，提供了工厂的一切基本操作，获取实例、判断实例种类、类型、是否存在

``` Java
public interface BeanFactory {
  //FactoryBean的转义附，一种在Spring中特色的Bean，后头会讲到
  //这里未被转义的都是我们认知的普通Bean
  String FACTORY_BEAN_PREFIX = "&";
  /*
   * 以下提供了5种获取Bean的方法：
   * 1、通过BeanName，在获取xml中的bean id时比较常用，常常id唯一
   * 2、通过BeanName与类型，通过类型校验，避免同名不同类的错误
   * 3、通过BeanName与构造方法参数获取
   * 4、通过类型获取，依然有可能出现多Bean的错误
   * 5、通过类型与构造方法参数获取
   * 带构造方法参数的获取方法，一般用在non-singleton的Bean
   * 具体可以看@Scope的说明
   */
   Object getBean(String name) throws BeansException;
   T> T getBean(String name, Class<T> requiredType) throws BeansException;
   Object getBean(String name, Object... args) throws BeansException;
   <T> T getBean(Class<T> requiredType) throws BeansException;
   <T> T getBean(Class<T> requiredType, Object... args) throws BeansException;

  /*
   * ObjectProvider是spring 4.3提供的注入方式，针对构造方法依赖注入做的可选方式
   */
   <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType);
   <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType);

  //查找是否存在Bean，本容器没有，会往上一层继续找
  boolean containsBean(String name);
  //是否单例
  boolean isSingleton(String name) throws NoSuchBeanDefinitionException;
  //是否原型
  boolean isPrototype(String name) throws NoSuchBeanDefinitionException;
  //是否有匹配的类型
  boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException;
  boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException;

  @Nullable
  Class<?> getType(String name) throws NoSuchBeanDefinitionException;
  String[] getAliases(String name);

}
```

- AutowireCapableBeanFactory，使spring容器外的实例对象可以优雅地使用容器内的任意一个Bean。

该接口在用户代码中很少会用到，但由于需要集成其他框架时，可以优雅的实例化prototype，具体可以常见quartz的Job接口，Job的实现类不在容器中也能注入使用@Autowire。

``` Java
public interface AutowireCapableBeanFactory extends BeanFactory {
  //成员变量使用@Autowire，可以不指定注入模式
  int AUTOWIRE_NO = 0;
  //以下两种针对不使用注解与构造方法，必须有seter方法
  int AUTOWIRE_BY_NAME = 1;
  int AUTOWIRE_BY_TYPE = 2;
  //通过没有注解且有构造器
  int AUTOWIRE_CONSTRUCTOR = 3;

  @Deprecated
  int AUTOWIRE_AUTODETECT = 4;
  String ORIGINAL_INSTANCE_SUFFIX = ".ORIGINAL";
  //以下方法均是装配existingBean，不同方法的实现可以阅读AbstractAutowireCapableBeanFactory
  <T> T createBean(Class<T> beanClass) throws BeansException;
  void autowireBean(Object existingBean) throws BeansException;
  Object configureBean(Object existingBean, String beanName) throws BeansException;
  Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException;
  Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException;
  void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException;
  //以下是对实例化对象的后处理
  Object initializeBean(Object existingBean, String beanName) throws BeansException;
  Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException;
  Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException;
  void destroyBean(Object existingBean);

}
```

- HierarchicalBeanFactory，获取上一层的容器

该方法是为了容器的分层，差不多父xml与子xml的关系。

``` Java
public interface HierarchicalBeanFactory extends BeanFactory {

	@Nullable
	BeanFactory getParentBeanFactory();
	boolean containsLocalBean(String name);

}
```

- ListableBeanFactory，可列表化的工厂

该接口提供的方法，都能让你简单粗暴地获取你想要的local bean，无需通过bean一个一个找。

``` Java
public interface ListableBeanFactory extends BeanFactory {
  //查找beanName是否存在
  boolean containsBeanDefinition(String beanName);
  //获取beanDefinitionMap.size()
  int getBeanDefinitionCount();
  //获取所有BeanName，在配置被冻结之前，获取的列表可能会变化
  String[] getBeanDefinitionNames();
  String[] getBeanNamesForType(ResolvableType type);
  String[] getBeanNamesForType(@Nullable Class<?> type);
  String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit);
  //获取beanName-bean的Map
  //includeNonSingletons是否包含non-singleton的Bean
  //allowEagerInit是否对懒加载的Bean进行初始化
  <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException;
  <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException;
  //下面的均是找到对应注解的Bean
  String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType);
  Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) throws BeansException;
  @Nullable
  <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException;

}
```

因为Spring容器本身太复杂，所以本文就着BeanFactory接口，说明了不同接口的作用，还未深入具体其实现。
希望后续的几篇文章能加快进度，尽快理清spring每个体系的关系。
