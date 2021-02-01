/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

    private final XPathParser parser;
    private final MapperBuilderAssistant builderAssistant;
    private final Map<String, XNode> sqlFragments;
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    public void parse() {
        if (!configuration.isResourceLoaded(resource)) {
            /**
             * <mapper namespace="org.apache.ibatis.autoconstructor.AutoConstructorMapper">
             *    <resultMap id="userResultMap" type="User">
             *     <constructor>
             *       <idArg column="blog_id" javaType="int"/>
             *     </constructor>
             *     <result property="title" column="blog_title"/>
             *     <association property="author" javaType="Author">
             *       <id property="id" column="author_id"/>
             *       <result property="username" column="author_username"/>
             *       <result property="password" column="author_password"/>
             *       <result property="email" column="author_email"/>
             *       <result property="bio" column="author_bio"/>
             *       <result property="favouriteSection" column="author_favourite_section"/>
             *     </association>
             *     <collection property="posts" ofType="Post">
             *       <id property="id" column="post_id"/>
             *       <result property="subject" column="post_subject"/>
             *       <association property="author" javaType="Author"/>
             *       <collection property="comments" ofType="Comment">
             *       <id property="id" column="comment_id"/>
             *     </collection>
             *     <collection property="tags" ofType="Tag" >
             *       <id property="id" column="tag_id"/>
             *      </collection>
             *      <discriminator javaType="int" column="draft">
             *          <case value="1" resultType="DraftPost"/>
             *      </discriminator>
             *     </collection>
             *    </resultMap>
             *
             *    <select id="selectUsers" resultMap="userResultMap">
             *      select user_id, user_name, hashed_password
             *      from some_table
             *      where id = #{id}
             *    </select>
             * </mapper>
             */
            configurationElement(parser.evalNode("/mapper"));
            // 添加资源路径到“已解析资源集合”中
            configuration.addLoadedResource(resource);
            // 通过命名空间绑定 Mapper 接口
            bindMapperForNamespace();
        }
        //再次迭代处理解析失败的ResultMapResolver再次解析器
        parsePendingResultMaps();
        //再次迭代处理解析失败的CacheRefResolver再次解析器
        parsePendingCacheRefs();
        //再次迭代构建处理失败的建造者，XMLStatementBuilder再次构建
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * <mapper namespace="org.apache.ibatis.autoconstructor.AutoConstructorMapper">
     * <resultMap id="userResultMap" type="User">
     * <constructor>
     * <idArg column="blog_id" javaType="int"/>
     * </constructor>
     * <result property="title" column="blog_title"/>
     * <association property="author" javaType="Author">
     * <id property="id" column="author_id"/>
     * <result property="username" column="author_username"/>
     * <result property="password" column="author_password"/>
     * <result property="email" column="author_email"/>
     * <result property="bio" column="author_bio"/>
     * <result property="favouriteSection" column="author_favourite_section"/>
     * </association>
     * <collection property="posts" ofType="Post">
     * <id property="id" column="post_id"/>
     * <result property="subject" column="post_subject"/>
     * <association property="author" javaType="Author"/>
     * <collection property="comments" ofType="Comment">
     * <id property="id" column="comment_id"/>
     * </collection>
     * <collection property="tags" ofType="Tag" >
     * <id property="id" column="tag_id"/>
     * </collection>
     * <discriminator javaType="int" column="draft">
     * <case value="1" resultType="DraftPost"/>
     * </discriminator>
     * </collection>
     * </resultMap>
     *
     * <select id="selectUsers" resultMap="userResultMap">
     * select user_id, user_name, hashed_password
     * from some_table
     * where id = #{id}
     * </select>
     * </mapper>
     */
    private void configurationElement(XNode context) {
        try {
            /**获取 mapper 命名空间
             * <mapper namespace="org.apache.ibatis.autoconstructor.AutoConstructorMapper">
             *  </mapper>
             */
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            // 设置命名空间到 builderAssistant 中
            builderAssistant.setCurrentNamespace(namespace);
            // 解析 <cache-ref> 节点
            /**
             * <mapper namespace="org.apache.ibatis.submitted.xml_external_ref.MultipleCrossIncludePersonMapper">
             *     MultipleCrossIncludePersonMapper 与 MultipleCrossIncludePetMapper 共用一个二级缓存
             * 	<cache-ref namespace="org.apache.ibatis.submitted.xml_external_ref.MultipleCrossIncludePetMapper" />
             *</mapper>
             */
            cacheRefElement(context.evalNode("cache-ref"));
            // 解析 <cache> 节点
            cacheElement(context.evalNode("cache"));
            // 解析 <resultMap> 节点
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            //解析<resultMap id="userResultMap" type="User"></resultMap>
            resultMapElements(context.evalNodes("/mapper/resultMap"));
            // 解析 <sql> 节点
            sqlElement(context.evalNodes("/mapper/sql"));
            // 解析 <select>、、<insert>、<update>、<delete> 等节点
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            // 调用重载方法构建 Statement
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        // 调用重载方法构建 Statement，requiredDatabaseId 参数为空
        buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            // 创建 Statement 建造类
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                // 解析 Statement 节点，并将解析结果存储到configuration 的 mappedStatements 集合中
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                // 解析失败，将解析器放入 Configuration 的 incompleteStatements 集合中
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        //获取ResultMapResolver解析列表
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        // 获取 CacheRefResolver 列表
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            // 通过迭代器遍历列表
            while (iter.hasNext()) {
                try {
                    // 尝试解析 <cache-ref> 节点，若解析失败，则抛出
                    // IncompleteElementException，此时下面的删除操作不会被执行
                    iter.next().resolveCacheRef();
                    // 移除 CacheRefResolver 对象。如果代码能执行到此处，
                    // 表明已成功解析了 <cache-ref> 节点
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                    // 如果再次发生 IncompleteElementException 异常，表明当前
                    // 映射文件中并没有<cache-ref>所引用的缓存。有可能所引用的缓存
                    // 在后面的映射文件中，所以这里不能将解析失败的 CacheRefResolver
                    // 从集合中删除
                }
            }
        }
    }

    private void parsePendingStatements() {
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().parseStatementNode();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    private void cacheRefElement(XNode context) {
        if (context != null) {
            /**
             * <cache-ref namespace="org.apache.ibatis.submitted.xml_external_ref.MultipleCrossIncludePetMapper" />
             */
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            // 创建 CacheRefResolver 实例
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                // 解析参照缓存
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    private void cacheElement(XNode context) {
        if (context != null) {
            // <cache eviction="FIFO" flushInterval="60000" size="512" readOnly="true"/>
            // 获取各种属性
            String type = context.getStringAttribute("type", "PERPETUAL");
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
            String eviction = context.getStringAttribute("eviction", "LRU");
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
            Long flushInterval = context.getLongAttribute("flushInterval");
            Integer size = context.getIntAttribute("size");
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);
            boolean blocking = context.getBooleanAttribute("blocking", false);
            // 获取子节点配置
            Properties props = context.getChildrenAsProperties();
            // 构建缓存对象
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    private void resultMapElements(List<XNode> list) throws Exception {
        // 遍历 <resultMap> 节点列表
        for (XNode resultMapNode : list) {
            try {
                // 解析 resultMap 节点
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList(), null);
    }

    /**
     * <resultMap id="userResultMap" type="User">
     * <constructor>
     * <idArg column="blog_id" javaType="int"/>
     * </constructor>
     * <result property="title" column="blog_title"/>
     * <association property="author" javaType="Author">
     * <id property="id" column="author_id"/>
     * <result property="username" column="author_username"/>
     * <result property="password" column="author_password"/>
     * <result property="email" column="author_email"/>
     * <result property="bio" column="author_bio"/>
     * <result property="favouriteSection" column="author_favourite_section"/>
     * </association>
     * <collection property="posts" ofType="Post">
     * <id property="id" column="post_id"/>
     * <result property="subject" column="post_subject"/>
     * <association property="author" javaType="Author"/>
     * <collection property="comments" ofType="Comment">
     * <id property="id" column="comment_id"/>
     * </collection>
     * <collection property="tags" ofType="Tag" >
     * <id property="id" column="tag_id"/>
     * </collection>
     * <discriminator javaType="int" column="draft">
     * <case value="1" resultType="DraftPost"/>
     * </discriminator>
     * </collection>
     * </resultMap>
     */
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) throws Exception {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        //<resultMap id="userResultMap" type="User"></resultMap>
        // 获取id属性
        String id = resultMapNode.getStringAttribute("id",
                resultMapNode.getValueBasedIdentifier());
        // 获取type属性，去别名、或者简单类名
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));
        //TODO 未使用，暂时不分析 获取 extends 和 autoMapping
        String extend = resultMapNode.getStringAttribute("extends");
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        // 解析 type 属性对应的类型
        Class<?> typeClass = resolveClass(type);
        if (typeClass == null) {
            typeClass = inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;

        List<ResultMapping> resultMappings = new ArrayList<>();
        resultMappings.addAll(additionalResultMappings);
        // 获取并遍历 <resultMap> 的子节点列表<constructor>、<result><id>、 <association>、<collection>
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            if ("constructor".equals(resultChild.getName())) {
                // 解析 constructor 节点，并生成相应的 ResultMapping
                processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                // 解析 discriminator 节点
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    // 添加 ID 到 flags 集合中
                    flags.add(ResultFlag.ID);
                }
                // 解析 id 和 property 节点，并生成相应的 ResultMapping
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        // 创建 ResultMap 解析器
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            /**
             * 根据前面获取到的信息构建 ResultMap 对象
             * 委托builderAssistant构建ResultMap
             */

            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            /**
             * 将失败的解析器放入失败列表，以便解析完成之后，进行第二轮解析，解析成功后从失败列表中移除
             */
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        /**
         * <constructor>
         *    <idArg column="blog_id" javaType="int"/>
         *    <arg column="name" name="name"/>
         * </constructor>
         */
        // 获取子节点列表
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<>();
            // 向 flags 中添加 CONSTRUCTOR 标志
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                // 向 flags 中添加 ID 标志
                flags.add(ResultFlag.ID);
            }
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Map<String, String> discriminatorMap = new HashMap<>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
            discriminatorMap.put(value, resultMap);
        }
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    private void sqlElement(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            // 调用 sqlElement 解析 <sql> 节点
            sqlElement(list, configuration.getDatabaseId());
        }
        // 再次调用 sqlElement，不同的是，这次调用，该方法的第二个参数为 null
        sqlElement(list, null);
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            // 获取 id 和 databaseId 属性
            String databaseId = context.getStringAttribute("databaseId");
            String id = context.getStringAttribute("id");
            // 拼接currentNamespace与id
            id = builderAssistant.applyCurrentNamespace(id, false);
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                // 将 <id, XNode> 键值对缓存到 sqlFragments 中，后面解析Select标签时使用
                sqlFragments.put(id, context);
            }
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            // 当前 databaseId 和目标 databaseId 不一致时，返回 false
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            // 如果目标 databaseId 为空，但当前 databaseId 不为空。两者不一致，返回 false
            if (databaseId != null) {
                return false;
            }
            // 如果当前 <sql> 节点的 id 与之前的 <sql> 节点重复，且先前节点
            // databaseId 不为空。则忽略当前节点，并返回 false
            // skip this fragment if there is a previous one with a not null databaseId
            if (this.sqlFragments.containsKey(id)) {
                XNode context = this.sqlFragments.get(id);
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
        String property;
        // 根据节点类型获取 name 或 property 属性
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        // 获取其他各种属性
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        /**
         * 解析 resultMap 属性，该属性出现在 <association> 和 <collection> 节点中。
         * 若这两个节点不包含 resultMap 属性，则调用 processNestedResultMappings 方法
         * 解析嵌套 resultMap。
         */
        String nestedResultMap = context.getStringAttribute("resultMap",
                processNestedResultMappings(context, Collections.<ResultMapping>emptyList(), resultType));

        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");

        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        // 解析 javaType、typeHandler 的类型以及枚举类型 JdbcType
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        // 构建 ResultMapping 对象
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) throws Exception {
        // 判断节点名称
        if ("association".equals(context.getName())
                || "collection".equals(context.getName())
                || "case".equals(context.getName())) {
            if (context.getStringAttribute("select") == null) {
                validateCollection(context, enclosingType);
                // resultMapElement 是解析 ResultMap 入口方法
                ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
                return resultMap.getId();
            }
        }
        return null;
    }

    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
                && context.getStringAttribute("resultType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException(
                        "Ambiguous collection type for property '" + property + "'. You must specify 'resultType' or 'resultMap'.");
            }
        }
    }

    private void bindMapperForNamespace() {
        // 获取映射文件的命名空间
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                // 根据命名空间解析 mapper 类型
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                // 检测当前 mapper 类是否被绑定过
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    configuration.addLoadedResource("namespace:" + namespace);
                    // 绑定 mapper 类
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
