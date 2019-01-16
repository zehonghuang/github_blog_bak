---
title: 【Spring源码分析】BeanFactory的生命周期
date: 2018-11-26 11:08:40
tags:
  - java　
  - Spring
  - AOP
  - IOC控制反转
  - BeanFactory
  - BeanFactory生命周期
categories:
    - Spring源码分析
---

---

两级别生命周期：
  - 容器级别 
    - BeanFactoryPostProcessor
    - BeanDefinitionRegistryPostProcessor
    - 调用实现逻辑
      PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors
  - Bean级别

- InstantiationAwareBeanPostProcessor

  - postProcessBeforeInstantiation

  - postProcessAfterInstantiation

  - postProcessProperties
