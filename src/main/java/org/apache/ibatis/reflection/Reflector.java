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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */

/**
 * Reflector 这个类的用途主要是是通过反射获取目标类的 getter 方法及其返回值类型，
 * setter 方法及其参数值类型等元信息。并将获取到的元信息缓存到相应的集合中，供后续使
 * 用。Reflector 本身代码比较多，这里不能一一分析。我将会分析该类的部分逻辑，分别如下：
 * 1. Reflector 构造方法及成员变量分析
 * 2. getter 方法解析过程
 * 3. setter 方法解析过程
 */
public class Reflector {

  private final Class<?> type;
  /**
   * 用于保存属性名的get方法名
   */
  private final String[] readablePropertyNames;
  /**
   * 用于保存属性名的时set方法名
   */
  private final String[] writeablePropertyNames;
  /**
   * 用于保存属性名称到 Invoke 的映射。setter 方法会被封装
   * 到 MethodInvoker 对象中，Invoke 实现类比较简单
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  /**
   * 用于保存属性名称到 Invoke 的映射。同上，getter 方法也
   * 会被封装到 MethodInvoker 对象中
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  /**
   * 用于保存 setter 对应的属性名与参数类型的映射
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  /**
   * 用于保存 getter 对应的属性名与返回值类型的映射
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  private Constructor<?> defaultConstructor;
  /**
   * 用于保存大写属性名与属性名之间的映射，比如 <NAME,name>
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    type = clazz;
    // 解析目标类的默认构造方法，并赋值给 defaultConstructor 变量
    addDefaultConstructor(clazz);
    // 解析 getter 方法，并将解析结果放入 getMethods 中
    addGetMethods(clazz);
    // 解析 setter 方法，并将解析结果放入 setMethods 中
    addSetMethods(clazz);
    // 解析属性字段，并将解析结果添加到 setMethods 或 getMethods 中
    addFields(clazz);
    // 从 getMethods 映射中获取可读属性名数组
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    // 从 setMethods 映射中获取可写属性名数组
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    // 将所有属性名的大写形式作为键，属性名作为值，
    // 存入到 caseInsensitivePropertyMap 中
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      if (constructor.getParameterTypes().length == 0) {
          this.defaultConstructor = constructor;
      }
    }
  }

  private void addGetMethods(Class<?> cls) {
    /**
     * 1. 获取当前类，接口，以及父类中的方法
     * 2. 遍历上一步获取的方法数组，并过滤出以 get 和 is 开头的方法
     * 3. 将方法名转换成相应的属性名
     * 4. 将属性名和方法对象添加到冲突集合中
     * 5. 解决冲突
     */
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 获取当前类，接口，以及父类中的方法。该方法逻辑不是很复杂，这里就不展开了
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      // getter 方法不应该有参数，若存在参数，则忽略当前方法
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      String name = method.getName();
      // 过滤出以 get 或 is 开头的方法
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
        // 将 getXXX 或 isXXX 等方法名转成相应的属性，比如 getName -> name
        name = PropertyNamer.methodToProperty(name);
        /**
         * 将冲突的方法添加到 conflictingGetters 中。考虑这样一种情况：
         * getTitle 和 isTitle 两个方法经过 methodToProperty 处理，
         * 均得到 name = title，这会导致冲突。
         * 对于冲突的方法，这里先统一起存起来，后续再解决冲突
         */
        addMethodConflict(conflictingGetters, name, method);
      }
    }

    // 解决 getter 冲突
    resolveGetterConflicts(conflictingGetters);
  }
  /** 解决冲突 */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    /**
     * 1. 冲突方法返回值类型具有继承关系，子类返回值对应方法被认为是更合适的选择
     * 2. 冲突方法的返回值类型相同，如果返回值类型为 boolean，那么以 is 开头的方法
     * 则是更合适的选择
     * 3. 冲突方法的返回值类型相同，但类型非 boolean，此时出现歧义，抛出异常
     * 4. 冲突方法的返回值类型不相关，无法确定哪个是更好的选择，此时直接抛异常
     * 分析完 getter 方法的解析过程，下面继续分析 setter 方法的解析过程。
     */
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      for (Method candidate : entry.getValue()) {
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 获取返回值类型
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        /**
         * 两个方法的返回值类型一致，若两个方法返回值类型均为 boolean，
         * 则选取 isXXX 方法为 winner。否则无法决定哪个方法更为合适，
         * 只能抛出异常
         */
        if (candidateType.equals(winnerType)) {
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
            /*
             * 如果方法返回值类型为 boolean，且方法名以 "is" 开头，
             * 则认为候选方法 candidate 更为合适
             */
          } else if (candidate.getName().startsWith("is")) {
            /*
             * winnerType 是 candidateType 的子类，类型上更为具体，
             * 则认为当前的 winner 仍是合适的，无需做什么事情
             */
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {
          /*
           * candidateType 是 winnerType 的子类，此时认为 candidate 方法
           * 更为合适，故将 winner 更新为 candidate
           */
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
        } else {
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      // 将筛选出的方法添加到 getMethods 中，并将方法返回值添加到 getTypes 中
      addGetMethod(propName, winner);
    }
  }

  private void addGetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      getMethods.put(name, new MethodInvoker(method));
      // 解析返回值类型
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      // 将返回值类型由 Type 转为 Class，并将转换后的结果缓存到 setTypes 中
      getTypes.put(name, typeToClass(returnType));
    }
  }

  private void addSetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    // 获取当前类，接口，以及父类中的方法。该方法逻辑不是很复杂，这里就不展开了
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      // 获取当前类，接口，以及父类中的方法。该方法逻辑不是很复杂，这里就不展开了
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          name = PropertyNamer.methodToProperty(name);
          // setter 方法发生冲突原因是：可能存在重载情况，比如：
          // void setSex(int sex);
          // void setSex(SexEnum sex);
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    // 解决 setter 冲突
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
    list.add(method);
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    /**
     * 1. 冲突方法的参数类型与 getter 的返回类型一致，则认为是最好的选择
     * 2. 冲突方法的参数类型具有继承关系，子类参数对应的方法被认为是更合适的选择
     * 3. 冲突方法的参数类型不相关，无法确定哪个是更好的选择，此时直接抛异常
     */
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      /*
       * 获取 getter 方法的返回值类型，由于 getter 方法不存在重载的情况，
       * 所以可以用它的返回值类型反推哪个 setter 的更为合适
       */
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      ReflectionException exception = null;
      for (Method setter : setters) {
        // 获取参数类型
        Class<?> paramType = setter.getParameterTypes()[0];
        if (paramType.equals(getterType)) {
          // 参数类型和返回类型一致，则认为是最好的选择，并结束循环
          match = setter;
          break;
        }
        if (exception == null) {
          try {
            // 选择一个更为合适的方法
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      // 若 match 为空，表示没找到更为合适的方法，此时抛出异常
      if (match == null) {
        throw exception;
      } else {
        // 将筛选出的方法放入 setMethods 中，并将方法参数值添加到 setTypes 中
        addSetMethod(propName, match);
      }
    }
  }
  /** 从两个 setter 方法中选择一个更为合适方法 */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    // 如果参数 2 可赋值给参数 1，即参数 2 是参数 1 的子类，
    // 则认为参数 2 对应的 setter 方法更为合适
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
      // 这里和上面情况相反
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    // 两种参数类型不相关，这里抛出异常
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      // 解析参数类型列表
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      // 将参数类型由 Type 转为 Class，并将转换后的结果缓存到 setTypes
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = cls;
    while (currentClass != null && currentClass != Object.class) {
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
