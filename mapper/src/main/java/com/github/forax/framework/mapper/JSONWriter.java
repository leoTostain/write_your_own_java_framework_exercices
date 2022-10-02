package com.github.forax.framework.mapper;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.*;

public final class JSONWriter {
  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  private static List<PropertyDescriptor> beanProperties(Class<?> type) {
    var beanInfo = Utils.beanInfo(type);
    return Arrays.stream(beanInfo.getPropertyDescriptors()).toList();
  }

  private static List<PropertyDescriptor> recordProperties(Class<?> type) throws IntrospectionException {
    var components = type.getRecordComponents();
    List<PropertyDescriptor> descriptors = new ArrayList<>();
    for(RecordComponent component: components) {
      var name = component.getName();
      var accessor = component.getAccessor();
      descriptors.add(new PropertyDescriptor(name, accessor, null));
    }
    return descriptors;
  }
  private static final ClassValue<List<Generator>> PROPERTIES_CLASS_VALUES = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      List<PropertyDescriptor> properties;
      if (type.isRecord()) {
        try {
          properties = recordProperties(type);
        } catch (IntrospectionException e) {
          throw new RuntimeException(e);
        }
      } else {
        properties = beanProperties(type);
      }
      return properties.stream()
              .filter(property -> !property.getName().equals("class"))
              .<Generator>map(property -> {
                String value;
                if (property.getReadMethod().isAnnotationPresent(JSONProperty.class)) {
                  value = property.getReadMethod().getAnnotation(JSONProperty.class).value();
                } else {
                  value = property.getName();
                }
                var key = "\"" + value + "\": ";
                var getter = property.getReadMethod();
                return (writer, bean) -> key + writer.toJSON(Utils.invokeMethod(bean, getter));
              })
              .toList();
    }
  };

  public String toJSON(Object o) {
    return switch (o) {
      case null -> "null";
      case Boolean b -> String.valueOf(b);
      case String s -> "\"" + s + "\"";
      case Integer i -> String.valueOf(i);
      case Double d -> String.valueOf(d);
      case Object ob -> {
        var fun = map.get(ob.getClass());
        if(fun != null) {
          yield fun.apply(ob);
        }
        var generators = PROPERTIES_CLASS_VALUES.get(ob.getClass());
        yield generators.stream()
                .map(generator -> generator.generate(this, ob))
                .collect(joining(", ", "{", "}"));
      }
    };
  }

  private final HashMap<Class<?>, Function<Object, String>> map = new HashMap<>();
  public <T> void configure(Class<T> type, Function<T, String> fun) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(fun);

    var result = map.putIfAbsent(type, o -> fun.apply(type.cast(o)));
    if(result != null) {
      throw new IllegalStateException("configuration for " + type.getName() + "already exists");
    }
  }
}

