---
title: SpringMVC拦截器Interceptor
date: 2016-05-11 00:26:08
categories:
- Java
tags:
- java
- spring
---

SpringMVC拦截器是个非常好用的东西，对于每个请求有分别对进入控制器前、执行控制器后渲染之前、渲染之后的行为都能拦截。通常我们用拦截器实现了权限管理、MyBatis分页功能等。

---

- ### 实现`HandlerInterceptor`接口

拦截器一般直接实现`HandlerInterceptor`接口，Spring也只处理该类型的拦截器。有些文章说也可以选择继承`HandlerInterceptorAdapter`抽象类，此方式我强烈不建议，不仅因为该类并不是严格的拦截器接口，也浪费了继承位置，并且`afterConcurrentHandlingStarted`方法在其实并没有什么卵用，Spring完全调用不到该方法。

``` java
public class FuckInterceptor implements HandlerInterceptor {

  //进入控制器之前执行,若为false,不执行控制器.
  public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o) throws Exception {

      return true;
  }

  //出控制器后,若有新页面并未渲染之前执行
  public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o, ModelAndView modelAndView) throws Exception {

  }

  //整个请求结束之后
  public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o, Exception e) throws Exception {

  }
}

// ===============================================================
public abstract class HandlerInterceptorAdapter implements AsyncHandlerInterceptor {
}

public interface AsyncHandlerInterceptor extends HandlerInterceptor {
  void afterConcurrentHandlingStarted(
			HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception;
}
```

- ### 实现`WebRequestInterceptor`接口

其次还有实现`WebRequestInterceptor`接口的方式，为`WebRequest`已经封装`HttpServletRequest`的全部常用操作。但此类拦截器并不能让如上述拦截器一般，停止请求继续进行，所以通常只做请求的预备工作。

``` java
public class FuckInterceptor implements WebRequestInterceptor {

  //进入控制器之前执行.
  public void preHandle(WebRequest request) throws Exception {}

  //出控制器后,若有新页面并未渲染之前执行
  public void postHandle(WebRequest request, ModelMap model) throws Exception {}

  //整个请求结束之后
  public void afterCompletion(WebRequest request, Exception ex) throws Exception {}
}

public interface WebRequest extends RequestAttributes {

	String getHeader(String headerName);

	String[] getHeaderValues(String headerName);

	Iterator<String> getHeaderNames();

	String getParameter(String paramName);

	String[] getParameterValues(String paramName);

	Iterator<String> getParameterNames();

	Map<String, String[]> getParameterMap();

	Locale getLocale();

	String getContextPath();

	String getRemoteUser();

	Principal getUserPrincipal();

	boolean isUserInRole(String role);

	boolean isSecure();

	boolean checkNotModified(long lastModifiedTimestamp);

	boolean checkNotModified(String etag);

	boolean checkNotModified(String etag, long lastModifiedTimestamp);

	String getDescription(boolean includeClientInfo);

}
```

- ### 配置拦截器

引用标签
``` xml
xmlns:mvc="http://www.springframework.org/schema/mvc"
xsi:schemaLocation="http://www.springframework.org/schema/mvc
  http://www.springframework.org/schema/mvc/spring-mvc.xsd"
```

配置拦截器
``` xml
<mvc:interceptors>
  <!-- 使用bean定义一个Interceptor，直接定义在mvc:interceptors根下面的Interceptor将拦截所有的请求 -->
  <mvc:interceptor>
    <mvc:mapping path="/fuck/**"/>
    <!-- 定义在mvc:interceptor下面的表示是对特定的请求才进行拦截的 -->
    <bean class="com.bayes.interceptor.WechatInterceptor"/>
  </mvc:interceptor>
</mvc:interceptors>
```
