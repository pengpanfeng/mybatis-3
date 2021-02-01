/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed;
  private final XPathParser parser;
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }
//XMLConfigBuilder解析XML配置文件，将xml数据转换成Configuration实体数据
  public Configuration parse() {
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 通过xpath形式，解析配置
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      // 通过xpath形式，解析properties标签，并将properties中属性配置解析到Configuration的variables成员中
      propertiesElement(root.evalNode("properties"));
      // 解析 settings 配置，并将其转换为 Properties 对象
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      // 加载 vfs
      loadCustomVfs(settings);
      //加载日志实现
      loadCustomLogImpl(settings);
      // 解析 typeAliases 配置
      //将别名注册到Configuration的typeAliasRegistry<String,Class>属性中,未显示表明别名，默认为简单类名
      typeAliasesElement(root.evalNode("typeAliases"));
      // 解析 plugins 配置
      // interceptorChain
      pluginElement(root.evalNode("plugins"));
      // 解析 objectFactory 配置
      objectFactoryElement(root.evalNode("objectFactory"));
      // 解析 objectWrapperFactory 配置
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 解析 reflectorFactory 配置
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      // settings 中的信息设置到 Configuration 对象中
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      // 解析 environments 配置
      environmentsElement(root.evalNode("environments"));
      // 解析 databaseIdProvider，获取并设置 databaseId 到 Configuration 对象
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 解析 typeHandlers 配置
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 解析 mappers 配置
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    /*
    <settings>
      <setting name="cacheEnabled" value="true"/>
      <setting name="lazyLoadingEnabled" value="true"/>
      <setting name="multipleResultSetsEnabled" value="true"/>
      <setting name="useColumnLabel" value="true"/>
      <setting name="useGeneratedKeys" value="false"/>
      <setting name="autoMappingBehavior" value="PARTIAL"/>
      <setting name="autoMappingUnknownColumnBehavior" value="WARNING"/>
      <setting name="defaultExecutorType" value="SIMPLE"/>
      <setting name="defaultStatementTimeout" value="25"/>
      <setting name="defaultFetchSize" value="100"/>
      <setting name="safeRowBoundsEnabled" value="false"/>
      <setting name="mapUnderscoreToCamelCase" value="false"/>
      <setting name="localCacheScope" value="SESSION"/>
      <setting name="jdbcTypeForNull" value="OTHER"/>
      <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
    </settings>
     */
    if (context == null) {
      return new Properties();
    }
    // 获取 settings 子节点中的内容
    Properties props = context.getChildrenAsProperties();
    // 创建 Configuration 类的“元信息”对象
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      /**
       * 检测 Configuration 中是否存在相关属性，不存在则抛出异常
       * 1、是否存在属性
       * 2、是否存在相关集合、实体、注册器等提供的add、setter、注册等方法
       */
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  private void typeAliasesElement(XNode parent) {
    /**
     * <typeAliases>
     *   <package name="domain.blog"/>
     *   <typeAlias alias="Author" type="domain.blog.Author"/>
     *   <typeAlias alias="Blog" type="domain.blog.Blog"/>
     * </typeAliases>
     */
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        //从指定的包中解析别名和类型的映射
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          //从 typeAlias 节点中解析别名和类型的映射
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            // 加载 type 对应的类型
            Class<?> clazz = Resources.classForName(type);
            // 注册别名到类型的映射
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    /**
     * <plugins>
     *   <plugin interceptor="org.mybatis.example.ExamplePlugin">
     *     <property name="someProperty" value="100"/>
     *   </plugin>
     * </plugins>
     */
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        // 获取配置信息
        Properties properties = child.getChildrenAsProperties();
        // 解析拦截器的类型，并创建拦截器
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        // 设置属性
        interceptorInstance.setProperties(properties);
        // 添加拦截器到 Configuration 中
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    /**
     * <objectFactory type="org.mybatis.example.ExampleObjectFactory">
     *   <property name="someProperty" value="100"/>
     * </objectFactory>
     */
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      /**
       * 优先读取别名注册器中缓存的type，如果没有注册，则通过ClassLoaderWrapper加载器加载
       */
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
       String type = context.getStringAttribute("type");
       ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
       configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    /*
    <properties resource="org/mybatis/example/config.properties">
      <property name="org.apache.ibatis.parsing.PropertyParser.enable-default-value" value="true"/> <!-- 启用默认值特性 -->
    </properties>
     */
    if (context != null) {
      // 解析 propertis 的子节点，并将这些节点内容转换为属性对象 Properties
      Properties defaults = context.getChildrenAsProperties();
      // 获取 propertis 节点中的 resource 和 url 属性值
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      //两者都不为空，则抛出异常
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        // 从文件系统中加载并解析属性文件
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        // 通过 url 加载并解析属性文件
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // 将属性值设置到 parser 中variables属性中，后面解析XML节点时使用
      parser.setVariables(defaults);
      // 将属性值设置到 configuration 中
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    // 设置 autoMappingBehavior 属性，默认值为 PARTIAL
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    // 设置 cacheEnabled 属性，默认值为 true
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    // 设置默认枚举处理器
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * environments中可配置多个environment子节点，在初始化解析式时只只解析environment中id为environments中default指定的environment
   * @param context
   * @throws Exception
   */
  private void environmentsElement(XNode context) throws Exception {
    /**
     * <environments default="development">
     *   <environment id="development">
     *     <transactionManager type="JDBC">
     *       <property name="..." value="..."/>
     *     </transactionManager>
     *     <dataSource type="POOLED">
     *       <property name="driver" value="${driver}"/>
     *       <property name="url" value="${url}"/>
     *       <property name="username" value="${username}"/>
     *       <property name="password" value="${password}"/>
     *     </dataSource>
     *   </environment>
     * </environments>
     */
    if (context != null) {
      if (environment == null) {
        // 获取 default 属性
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        // 获取 id 属性
        String id = child.getStringAttribute("id");
        // 检测当前 environment 节点的 id 与其父节点 environments 的
        // 属性 default 内容是否一致，一致则返回 true，否则返回 false
        if (isSpecifiedEnvironment(id)) {
          // 解析 transactionManager 节点
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 解析 dataSource 节点
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          // 创建 DataSource 对象
          DataSource dataSource = dsFactory.getDataSource();
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          // 构建 Environment 对象，并设置到 configuration 中
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
          type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      /**
       * <transactionManager type="JDBC">
       *    <property name="..." value="..."/>
       * </transactionManager>
       */
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      /**
       * Configuration构造方法中注册  JDBC -> TransactionFactory
       * 通过别名POOLED解析数据源工厂
       */
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      /**
       * <dataSource type="POOLED">
       *    <property name="driver" value="${driver}"/>
       *    <property name="url" value="${url}"/>
       *    <property name="username" value="${username}"/>
       *    <property name="password" value="${password}"/>
       * </dataSource>
       */
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();

      /**
       * Configuration构造方法中注册 POOLED -> DataSourceFactory
       * 通过别名POOLED解析数据源工厂
       */
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) {

    if (parent != null) {
      /**
       *
       * <typeHandlers>
       *    <package name="org.mybatis.example"/>
            <typeHandler javaTyp="" jdbcType=""  handler="org.mybatis.example.ExampleTypeHandler"/>
       * </typeHandlers>
       */
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          /**
           *  <package name="org.mybatis.example"/>
           *  从指定的包中注册 TypeHandler
           */
          String typeHandlerPackage = child.getStringAttribute("name");
          // 注册方法
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          /**
           * <typeHandler javaTyp="" jdbcType=""  handler="org.mybatis.example.ExampleTypeHandler"/>
           * 从 typeHandler 节点中解析别名到类型的映射
           * ExampleTypeHandler 的作用
           * javaTyp -> jdbcType
           * jdbcType -> javaTyp
           */
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      /**
       * <mappers>
       *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/> 通过classpath资源路径解析
       *   <mapper url="file:///var/mappers/PostMapper.xml"/> 通过本地资源文件解析
       *   <mapper class="org.mybatis.builder.AuthorMapper"/> 通过类解析
       *   <package name="org.mybatis.builder"/> 通过包解析
       * </mappers>
       */
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          // 获取 <package> 节点中的 name 属性
          String mapperPackage = child.getStringAttribute("name");
          // 从指定包中查找 mapper 接口，并根据 mapper 接口解析映射配置
          configuration.addMappers(mapperPackage);
        } else {
          // 获取 resource/url/class 等属性
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // resource 不为空，且其他两者为空，则从指定路径中加载配置
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            // 解析AxxxMapper.xml映射文件
            mapperParser.parse();
            // url 不为空，且其他两者为空，则通过 url 加载配置
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            // 解析AxxxMapper.xml映射文件
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            // mapperClass 不为空，且其他两者为空，
            // 则通过 mapperClass 解析映射配置
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            // 以上条件不满足，则抛出异常
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
