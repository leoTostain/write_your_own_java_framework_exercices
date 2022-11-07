package com.github.forax.framework.orm;

import org.h2.store.Data;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.*;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {

    void run() throws SQLException;

  }
  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }
  private static class UncheckedSQLException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }
    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }



  }
  // --- do not change the code above

  private static final ThreadLocal<Connection> CONNECTION_THREAD_LOCAL = new ThreadLocal<>();
  static Connection currentConnection() {
    var connection = CONNECTION_THREAD_LOCAL.get();
    if(connection == null) {
      throw new IllegalStateException("This thread does not have any connection mapped");
    }
    return connection;
  }

  static String findTableName(Class<?> beanClass) {
    requireNonNull(beanClass);
    var annotation = beanClass.getAnnotation(Table.class);
    var name = annotation == null ? beanClass.getSimpleName() : annotation.value();
    return name.toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property) {
    requireNonNull(property);
    var annotation = property.getReadMethod().getAnnotation(Column.class);
    var name = annotation == null ? property.getName() : annotation.value();
    return name.toUpperCase(Locale.ROOT);
  }

  static String findColumnType(PropertyDescriptor property) {
    requireNonNull(property);
    var type = property.getPropertyType();
    var getter = property.getReadMethod();
    var name = TYPE_MAPPING.get(type);

    if(getter.getAnnotation(GeneratedValue.class) != null) {
      name += " AUTO_INCREMENT";
    }
    if(type.isPrimitive()) {
      name += " NOT NULL";
    }
    if(getter.getAnnotation(Id.class) != null) {
      name += ", PRIMARY KEY (" + findColumnName(property) + ")";
    }

    return name.toUpperCase(Locale.ROOT);
  }

  static void createTable(Class<?> beanType) throws SQLException {
    requireNonNull(beanType);
    var connection = CONNECTION_THREAD_LOCAL.get();
    if(connection == null) {
      throw new IllegalStateException();
    }
    var beanInfo = Utils.beanInfo(beanType);
    var tableName = findTableName(beanType);

    var properties = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .map(property -> findColumnName(property) + " " + findColumnType(property))
            .collect(Collectors.joining(", "));

    String query = " CREATE TABLE " + tableName + " ( " + properties +");";
    try(Statement statement = connection.createStatement()) {
      statement.executeUpdate(query);
    } catch (SQLException e) {
      throw new SQLException(e);
    }
    connection.commit();
  }

  static <T> T toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    try {
      var instance = (T) constructor.newInstance();
      for(var descriptor : beanInfo.getPropertyDescriptors()) {
        var method = descriptor.getWriteMethod();
        if(method != null) {
          var fieldName = method.getName().replace("set", "");
          Utils.invokeMethod(instance, method, resultSet.getObject(fieldName));
        }
      }
      return instance;
    } catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  static <T> List<T> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    Objects.requireNonNull(connection);
    Objects.requireNonNull(beanInfo);
    Objects.requireNonNull(constructor);
    var list = new ArrayList<T>();

    try(var statement = connection.prepareStatement(sqlQuery)) {
      try(ResultSet resultSet = statement.executeQuery()) {
        while(resultSet.next()) {
          list.add(toEntityClass(resultSet, beanInfo, constructor));
        }
      }
    }
    return list;
  }

  static <T> T createRepository(Class<T> repositoryType) {
    requireNonNull(repositoryType);
    return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(),
      new Class<?>[] {repositoryType},
      (proxy, method, args) -> {
        if(CONNECTION_THREAD_LOCAL.get() == null) {
          throw new IllegalStateException("Call of method must be in a transaction");
        }
        var beanInfo = Utils.beanInfo(findBeanTypeFromRepository(repositoryType));
        var className = beanInfo.getBeanDescriptor().getBeanClass();
        var tableName = className.getSimpleName().toUpperCase(Locale.ROOT);
        var sqlQuery = """
  SELECT * FROM %s;
""".formatted(tableName);

        return switch (method.getName()) {
          case "findAll" -> findAll(currentConnection(), sqlQuery, beanInfo, className.getConstructor());
          case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException();
          default -> throw new IllegalStateException();
        };
      }
    ));
  }

  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    requireNonNull(dataSource);
    requireNonNull(block);
    try(Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      CONNECTION_THREAD_LOCAL.set(connection);
      try {
        block.run();
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException e2) {
          e.addSuppressed(e2);
        }
        throw e;
      } finally {
        CONNECTION_THREAD_LOCAL.remove();
      }
    }
  }


}
