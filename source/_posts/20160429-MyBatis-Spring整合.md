---
title: MyBatis+Spring整合
date: 2016-04-29 11:52:56
categories:
- Java
tags:
- java
- spring
---
MyBatis整合，PageHelper分页插件，Spring事物管理。


#### MyBatis整合
在web.xml配置放jdbc.properties等配置文件的目录路径。
``` xml
<context-param>
        <param-name>PROP_HOME</param-name>
        <param-value>/Users/**/config</param-value>
</context-param>
```
在spring的spring-mvc.xml引入上面配的`PROP_HOME`。
``` xml
<bean class="org.springframework.beans.factory.config.PropertyPlaceholderConfigurer">
	<property name="locations">
		<list>
			<value>file:///${PROP_HOME}/jdbc.properties</value>
		</list>
	</property>
</bean>
```

用c3p0配置连接池。
``` xml
<bean id="dataSource" class="com.mchange.v2.c3p0.ComboPooledDataSource">
	<property name="jdbcUrl" value="${mysql.url}" />
	<property name="user" value="${mysql.username}" />
	<property name="password" value="${mysql.password}" />
	<property name="driverClass" value="${mysql.driverClassName}" />
	<property name="maxPoolSize" value="${mysql.maxPoolSize}"/>
	<property name="minPoolSize" value="${mysql.minPoolSize}"/>
	<property name="initialPoolSize" value="${mysql.initialPoolSize}"/>
	<property name="maxIdleTime" value="${mysql.maxIdleTime}"/>
	<property name="acquireIncrement" value="${mysql.acquireIncrement}"/>
	<property name="maxStatements" value="${mysql.maxStatements}"/>
	<property name="idleConnectionTestPeriod" value="${mysql.idleConnectionTestPeriod}"/>
</bean>
```

配置MyBatis。
``` xml
<bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">
	<property name="dataSource" ref="dataSource" />
	<!-- 自动匹配Mapper映射文件 -->
	<property name="mapperLocations" value="classpath*:mappings/**/*apper.xml"/>
 	<property name="typeAliasesPackage" value="com.bayes.entity"/>
	<property name="plugins">
		<array>
			<bean class="com.github.pagehelper.PageHelper">
				<property name="properties">
					<value>
						dialect=mysql
						offsetAsPageNum=true
						rowBoundsWithCount=true
						pageSizeZero=true
						reasonable=false
						supportMethodsArguments=false
						returnPageInfo=always
						params=pageNum=pageHelperStart;pageSize=pageHelperRows;
					</value>
				</property>
			</bean>
		</array>
	</property>
</bean>```

配置MyBatis注解。
``` xml
<bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
	<property name="basePackage" value="com.bayes.mapper" />
</bean>
```

jdbc.properties配置内容。

``` properties
mysql.driverClassName=com.mysql.jdbc.Driver
mysql.url=jdbc:mysql://*.*.*.*:3306/***?useUnicode:true&characterEncoding:UTF-8&allowMultiQueries:true&noAccessToProcedureBodies=true
mysql.username=***
mysql.password=**#**
mysql.initialPoolSize=20  
mysql.maxPoolSize=100  
mysql.minPoolSize=10  
mysql.maxIdleTime=600  
mysql.acquireIncrement=5  
mysql.maxStatements=5  
mysql.idleConnectionTestPeriod=60
```

---

#### PageHelper分页插件集成Spring，以及使用

PageHelper集成配置已经在上述`配置MyBatis`给出。
官方github有详细文档，可参照。

---

#### 配置Spring事物管理

配置Spring-jdbc事物。
``` xml
<bean id="transactionManager" class="org.springframework.jdbc.datasource.DataSourceTransactionManager">
	<property name="dataSource" ref="dataSource"/>
</bean>
```

匹配符合规则的方法。
``` xml
<tx:advice id="bayesAdvice" transaction-manager="transactionManager">
	<tx:attributes>
		<tx:method name="save*" propagation="REQUIRED"/>
		<tx:method name="del*" propagation="REQUIRED"/>
		<tx:method name="update*" propagation="REQUIRED"/>
		<tx:method name="add*" propagation="REQUIRED"/>
		<tx:method name="find*" propagation="REQUIRED"/>
		<tx:method name="get*" propagation="REQUIRED"/>
		<tx:method name="apply*" propagation="REQUIRED"/>
	</tx:attributes>
</tx:advice>
<aop:config>
	<aop:pointcut id="allAdviceServiceMethod" expression="execution(* com.bayes.service.*.*(..))"/>
	<aop:advisor pointcut-ref="allAdviceServiceMethod" advice-ref="bayesAdvice" />
</aop:config>
```
