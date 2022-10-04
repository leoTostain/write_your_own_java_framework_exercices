package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public final class InjectorRegistry {
    HashMap<Class<?>, Supplier> map = new HashMap<>();

    public <T> void registerInstance(Class<T> type, T instance) {
        requireNonNull(type);
        requireNonNull(instance);

        var result = map.putIfAbsent(type, () -> instance);
        if (result != null) {
            throw new IllegalStateException("Instance already exist for " + type.getName());
        }
    }

    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier) {
        requireNonNull(type);
        requireNonNull(supplier);

        var result = map.putIfAbsent(type, supplier);
        if (result != null) {
            throw new IllegalStateException("Supplier already exist for " + type.getName());
        }
    }

    //package private for test
    static List<PropertyDescriptor> findInjectableProperties(Class<?> className) {
        requireNonNull(className);
        var beanInfo = Utils.beanInfo(className);
        return Arrays.stream(beanInfo.getPropertyDescriptors()).filter((property -> {
            var setter = property.getWriteMethod();
            return setter != null && setter.isAnnotationPresent(Inject.class);
        })).toList();
    }

    public <T> T lookupInstance(Class<T> type) {
        requireNonNull(type);

        if (!map.containsKey(type)) {
            throw new IllegalStateException("No instance registered for " + type.getName());
        }
        return type.cast(map.get(type).get());
    }

    private Constructor<?> getConstructor(Class<?> providerClass) {
        var constructors = providerClass.getConstructors();
        var listConstructors = Arrays.stream(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .toList();
        var size = listConstructors.size();

        if (size == 0) {
            return Utils.defaultConstructor(providerClass);
        } else if (size == 1) {
            return listConstructors.get(0);
        } else {
            throw new IllegalStateException("More than one constructor with @Inject");
        }
    }

    public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass) {
        requireNonNull(type);
        requireNonNull(providerClass);
        var properties = findInjectableProperties(providerClass);
        var constructor = getConstructor(providerClass);

        registerProvider(type, () -> {
            var parameters = Arrays.stream(constructor.getParameterTypes())
                    .map(this::lookupInstance)
                    .toArray();
            var instance = Utils.newInstance(constructor, parameters);
            for (var property : properties) {
                var setter = property.getWriteMethod();
                var value = lookupInstance(setter.getParameterTypes()[0]);
                Utils.invokeMethod(instance, setter, value);
            }
            return type.cast(instance);
        });
    }

    public <T> void registerProviderClass(Class<T> type) {
        registerProviderClass(type, type);
    }
}
