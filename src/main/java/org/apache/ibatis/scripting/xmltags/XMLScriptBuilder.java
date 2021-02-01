/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

  private final XNode context;
  private boolean isDynamic;
  private final Class<?> parameterType;
  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

  public XMLScriptBuilder(Configuration configuration, XNode context) {
    this(configuration, context, null);
  }

  public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
    super(configuration);
    this.context = context;
    this.parameterType = parameterType;
    initNodeHandlerMap();
  }


  private void initNodeHandlerMap() {
    //注册 trim 节点解析器
    nodeHandlerMap.put("trim", new TrimHandler());
    //注册 where 节点解析器
    nodeHandlerMap.put("where", new WhereHandler());
    //注册 set 节点解析器
    nodeHandlerMap.put("set", new SetHandler());
    //注册 foreach 节点解析器
    nodeHandlerMap.put("foreach", new ForEachHandler());
    //注册 if 节点解析器
    nodeHandlerMap.put("if", new IfHandler());
    //注册 choose 节点解析器
    nodeHandlerMap.put("choose", new ChooseHandler());
    //注册 when 节点解析器
    nodeHandlerMap.put("when", new IfHandler());
    //注册 otherwise 节点解析器
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    //注册 bind 节点解析器
    nodeHandlerMap.put("bind", new BindHandler());
  }

  public SqlSource parseScriptNode() {
    // 解析 SQL 语句节点
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource = null;
    // 根据 isDynamic 状态创建不同的 SqlSource
    if (isDynamic) {
      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
  }

  protected MixedSqlNode parseDynamicTags(XNode node) {
    //用于存储解析 sql 片段
    List<SqlNode> contents = new ArrayList<>();
    NodeList children = node.getNode().getChildNodes();
    // 遍历子节点
    for (int i = 0; i < children.getLength(); i++) {
      XNode child = node.newXNode(children.item(i));
      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
        // 获取文本内容
        String data = child.getStringBody("");
        //将 data sql片段生成 TextSqlNode节点
        TextSqlNode textSqlNode = new TextSqlNode(data);
        /**
         * 若文本中包含 ${} 占位符，则被认为是动态节点
         */
        if (textSqlNode.isDynamic()) {
          contents.add(textSqlNode);
          isDynamic = true;
        } else {
          //若data为纯文本 sql片段生成 StaticTextSqlNode
          contents.add(new StaticTextSqlNode(data));
        }
      } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
        // child 节点是 ELEMENT_NODE 类型，比如 <if>、<where>、<trim>、<choose> 等
        String nodeName = child.getNode().getNodeName();
        /**
         * initNodeHandlerMap方法中，初始化注入常用XML处理器
         * 根据节点名称获取 NodeHandler
         */

        NodeHandler handler = nodeHandlerMap.get(nodeName);
        /**
         *  如果 handler 为空，表明当前节点对与 MyBatis 来说，是未知节点。
         *   MyBatis 无法处理这种节点，故抛出异常
         */

        if (handler == null) {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        /**
         * 将XNode -> SqlNode,然后将SqlNode添加到 contents
         *   BindHandler -> VarDeclSqlNode
         *   TrimHandler -> TrimSqlNode
         *   WhereHandler -> WhereSqlNode
         *   SetHandler ->  SetSqlNode
         *   ForEachHandler -> ForEachSqlNode
         *   IfHandler -> NodeHandler
         *   OtherwiseHandler -> NodeHandler
         *   ChooseHandler -> ChooseSqlNode
         */
        handler.handleNode(child, contents);
        isDynamic = true;
      }
    }
    return new MixedSqlNode(contents);
  }

  private interface NodeHandler {
    void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
  }


  private class BindHandler implements NodeHandler {
    public BindHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      final String name = nodeToHandle.getStringAttribute("name");
      final String expression = nodeToHandle.getStringAttribute("value");
      final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
      targetContents.add(node);
    }
  }

  private class TrimHandler implements NodeHandler {
    public TrimHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * <trim prefix="SET" suffixOverrides=",">
       *
       * </trim>
       */
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String prefix = nodeToHandle.getStringAttribute("prefix");
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      String suffix = nodeToHandle.getStringAttribute("suffix");
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
      targetContents.add(trim);
    }
  }

  private class WhereHandler implements NodeHandler {
    public WhereHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * SELECT * FROM BLOG
       *
       * <where>
       *     <if test="state != null">
       *          state = #{state}
       *     </if>
       *     <if test="title != null">
       *         AND title like #{title}
       *     </if>
       *     <if test="author != null and author.name != null">
       *         AND author_name like #{author.name}
       *     </if>
       *   </where>
       */
      // 调用 parseDynamicTags 解析 <where> 节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // 创建 WhereSqlNode
      WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
      // 添加到 targetContents
      targetContents.add(where);
    }
  }

  private class SetHandler implements NodeHandler {
    public SetHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * <update id="updateAuthorIfNecessary">
       *   update Author
       *     <set>
       *       <if test="username != null">username=#{username},</if>
       *       <if test="password != null">password=#{password},</if>
       *       <if test="email != null">email=#{email},</if>
       *       <if test="bio != null">bio=#{bio}</if>
       *     </set>
       *   where id=#{id}
       * </update>
       *
       *  update Author set username=? ,password=? email=? ,bio=?
       */
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
      targetContents.add(set);
    }
  }


  private class ForEachHandler implements NodeHandler {
    public ForEachHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * <select id="selectPostIn" resultType="domain.blog.Post">
       *   SELECT *
       *   FROM POST P
       *   WHERE ID in
       *   <foreach item="item" index="index" collection="list"
       *       open="(" separator="," close=")">
       *         #{item}
       *   </foreach>
       * </select>
       *
       * SELECT *  WHERE ID in ()
       */
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String collection = nodeToHandle.getStringAttribute("collection");
      String item = nodeToHandle.getStringAttribute("item");
      String index = nodeToHandle.getStringAttribute("index");
      String open = nodeToHandle.getStringAttribute("open");
      String close = nodeToHandle.getStringAttribute("close");
      String separator = nodeToHandle.getStringAttribute("separator");
      ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
      targetContents.add(forEachSqlNode);
    }
  }

  private class IfHandler implements NodeHandler {
    public IfHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * <select id="findActiveBlogLike" resultType="Blog">
       *   SELECT * FROM BLOG
       *   WHERE
       *   <if test="state != null">
       *     state = #{state}
       *   </if>
       *   <if test="title != null">
       *     AND title like #{title}
       *   </if>
       * </select>
       */
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String test = nodeToHandle.getStringAttribute("test");
      IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
      targetContents.add(ifSqlNode);
    }
  }

  private class OtherwiseHandler implements NodeHandler {
    public OtherwiseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * <select id="findActiveBlogLike"
       *      resultType="Blog">
       *   SELECT * FROM BLOG WHERE state = ‘ACTIVE’
       *   <choose>
       *     <when test="title != null">
       *       AND title like #{title}
       *     </when>
       *     <when test="author != null and author.name != null">
       *       AND author_name like #{author.name}
       *     </when>
       *     <otherwise>
       *       AND featured = 1
       *     </otherwise>
       *   </choose>
       * </select>
       */
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      targetContents.add(mixedSqlNode);
    }
  }

  private class ChooseHandler implements NodeHandler {
    public ChooseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      /**
       * <select id="findActiveBlogLike"
       *      resultType="Blog">
       *   SELECT * FROM BLOG WHERE state = ‘ACTIVE’
       *   <choose>
       *     <when test="title != null">
       *       AND title like #{title}
       *     </when>
       *     <when test="author != null and author.name != null">
       *       AND author_name like #{author.name}
       *     </when>
       *     <otherwise>
       *       AND featured = 1
       *     </otherwise>
       *   </choose>
       * </select>
       */
      List<SqlNode> whenSqlNodes = new ArrayList<>();

      List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
      //处理 otherwise、when 节点
      handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
      targetContents.add(chooseSqlNode);
    }

    private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
      List<XNode> children = chooseSqlNode.getChildren();
      for (XNode child : children) {
        String nodeName = child.getNode().getNodeName();
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler instanceof IfHandler) {
          handler.handleNode(child, ifSqlNodes);
        } else if (handler instanceof OtherwiseHandler) {
          handler.handleNode(child, defaultSqlNodes);
        }
      }
    }

    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      if (defaultSqlNodes.size() == 1) {
        defaultSqlNode = defaultSqlNodes.get(0);
      } else if (defaultSqlNodes.size() > 1) {
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      return defaultSqlNode;
    }
  }

}
