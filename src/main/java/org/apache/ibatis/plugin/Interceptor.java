/**
 *    Copyright 2009-2015 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.plugin;

import java.util.Properties;

/**
 * @author Clinton Begin
 */

/**
 * 插件是 MyBatis 提供的一个拓展机制，通过插件机制我们可在 SQL 执行过程中的某些
 * 点上做一些自定义操作。实现一个插件需要比简单，首先需要让插件类实现 Interceptor
 * 接口。然后在插件类上添加@Intercepts 和@Signature 注解，用于指定想要拦截的目标
 * 方法。MyBatis 允许拦截下面接口中的一些方法：
 * Executor: update，query，flushStatements，commit，rollback，getTransaction，
 * close，isClosed
 * ParameterHandler: getParameterObject，setParameters
 * ResultSetHandler: handleResultSets，handleOutputParameters
 * StatementHandler: prepare，parameterize，batch，update，query
 */
public interface Interceptor {

  Object intercept(Invocation invocation) throws Throwable;

  Object plugin(Object target);

  /**
   * 通过属性初始化拦截器
   * @param properties
   */
  void setProperties(Properties properties);

}
