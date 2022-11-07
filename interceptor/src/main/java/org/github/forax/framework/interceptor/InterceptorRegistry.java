package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public final class InterceptorRegistry {
  private HashMap<Class<?>, List<Interceptor>> interceptors = new HashMap<>();
  private HashMap<Method, Invocation> cache = new HashMap<>();

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) {
      requireNonNull(annotationClass);
      requireNonNull(aroundAdvice);
      addInterceptor(annotationClass, (instance, method, args, invocation) -> {
          aroundAdvice.before(instance, method, args);
          Object result = null;
          try {
              result = invocation.proceed(instance, method, args);
          } finally {
              aroundAdvice.after(instance, method, args, result);
          }
          return result;
      });
  }

    public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor) {
        requireNonNull(annotationClass);
        requireNonNull(interceptor);
        interceptors.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(interceptor);
        cache.clear();
    }

    protected List<Interceptor> findInterceptors(Method method) {
        requireNonNull(method);
        return Stream.of(
                        Arrays.stream(method.getDeclaringClass().getAnnotations()),
                        Arrays.stream(method.getAnnotations()),
                        Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream))
                .flatMap(s -> s)
                .distinct()
                .flatMap(annotation -> interceptors.getOrDefault(annotation.annotationType(), List.of()).stream())
                .toList();
    }

    static Invocation getInvocation(List<Interceptor> interceptorList) {
      Invocation invocation = Utils::invokeMethod;
      for (var interceptor : Utils.reverseList(interceptorList)) {
          var oldInvocation = invocation;
          invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, oldInvocation);
      }
      return invocation;
    }

  public <T> T createProxy(Class<T> type, T instance) {
      requireNonNull(type);
      requireNonNull(instance);
      return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
                var invocations = cache.computeIfAbsent(method, m -> {
                    var interceptors = findInterceptors(m);
                    return getInvocation(interceptors);
                });
                return invocations.proceed(instance, method, args);
            }
    ));
  }
}
