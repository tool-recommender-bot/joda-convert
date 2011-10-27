/*
 *  Copyright 2010 Stephen Colebourne
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.joda.convert;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manager for conversion to and from a {@code String}, acting as the main client interface.
 * <p>
 * Support is provided for conversions based on the {@link StringConverter} interface
 * or the {@link ToString} and {@link FromString} annotations.
 * <p>
 * StringConvert is thread-safe with concurrent caches.
 */
public final class StringConvert {

    /**
     * An immutable global instance.
     * <p>
     * This instance cannot be added to using {@link #register}, however annotated classes
     * are picked up. To register your own converters, simply create an instance of this class.
     */
    public static final StringConvert INSTANCE = new StringConvert();

    /**
     * The cache of converters.
     */
    private final ConcurrentMap<Class<?>, StringConverter<?>> registered = new ConcurrentHashMap<Class<?>, StringConverter<?>>();

    /**
     * Creates a new conversion manager including the JDK converters.
     */
    public StringConvert() {
        this(true);
    }

    /**
     * Creates a new conversion manager.
     * 
     * @param includeJdkConverters  true to include the JDK converters
     */
    public StringConvert(boolean includeJdkConverters) {
        if (includeJdkConverters) {
            for (JDKStringConverter conv : JDKStringConverter.values()) {
                registered.put(conv.getType(), conv);
            }
            registered.put(Boolean.TYPE, JDKStringConverter.BOOLEAN);
            registered.put(Byte.TYPE, JDKStringConverter.BYTE);
            registered.put(Short.TYPE, JDKStringConverter.SHORT);
            registered.put(Integer.TYPE, JDKStringConverter.INTEGER);
            registered.put(Long.TYPE, JDKStringConverter.LONG);
            registered.put(Float.TYPE, JDKStringConverter.FLOAT);
            registered.put(Double.TYPE, JDKStringConverter.DOUBLE);
            registered.put(Character.TYPE, JDKStringConverter.CHARACTER);
        }
    }

    //-----------------------------------------------------------------------
    /**
     * Converts the specified object to a {@code String}.
     * <p>
     * This uses {@link #findConverter} to provide the converter.
     * 
     * @param <T>  the type to convert from
     * @param object  the object to convert, null returns null
     * @return the converted string, may be null
     * @throws RuntimeException (or subclass) if unable to convert
     */
    @SuppressWarnings("unchecked")
    public <T> String convertToString(T object) {
        if (object == null) {
            return null;
        }
        Class<T> cls = (Class<T>) object.getClass();
        StringConverter<T> conv = findConverter(cls);
        return conv.convertToString(object);
    }

    /**
     * Converts the specified object to a {@code String}.
     * <p>
     * This uses {@link #findConverter} to provide the converter.
     * The class can be provided to select a more specific converter.
     * 
     * @param <T>  the type to convert from
     * @param cls  the class to convert from, not null
     * @param object  the object to convert, null returns null
     * @return the converted string, may be null
     * @throws RuntimeException (or subclass) if unable to convert
     */
    public <T> String convertToString(Class<T> cls, T object) {
        if (object == null) {
            return null;
        }
        StringConverter<T> conv = findConverter(cls);
        return conv.convertToString(object);
    }

    /**
     * Converts the specified object from a {@code String}.
     * <p>
     * This uses {@link #findConverter} to provide the converter.
     * 
     * @param <T>  the type to convert to
     * @param cls  the class to convert to, not null
     * @param str  the string to convert, null returns null
     * @return the converted object, may be null
     * @throws RuntimeException (or subclass) if unable to convert
     */
    public <T> T convertFromString(Class<T> cls, String str) {
        if (str == null) {
            return null;
        }
        StringConverter<T> conv = findConverter(cls);
        return conv.convertFromString(cls, str);
    }

    /**
     * Finds a suitable converter for the type.
     * <p>
     * This returns an instance of {@code StringConverter} for the specified class.
     * This could be useful in other frameworks.
     * <p>
     * The search algorithm first searches the registered converters.
     * It then searches for {@code ToString} and {@code FromString} annotations on the specified class.
     * Both searches consider superclasses.
     * 
     * @param <T>  the type of the converter
     * @param cls  the class to find a converter for, not null
     * @return the converter, not null
     * @throws RuntimeException (or subclass) if no converter found
     */
    @SuppressWarnings("unchecked")
    public <T> StringConverter<T> findConverter(final Class<T> cls) {
        if (cls == null) {
            throw new IllegalArgumentException("Class must not be null");
        }
        StringConverter<T> conv = (StringConverter<T>) registered.get(cls);
        if (conv == null) {
            if (cls == Object.class) {
                throw new IllegalStateException("No registered converter found: " + cls);
            }
            Class<?> loopCls = cls.getSuperclass();
            while (loopCls != null && conv == null) {
                conv = (StringConverter<T>) registered.get(loopCls);
                loopCls = loopCls.getSuperclass();
            }
            if (conv == null) {
                conv = findAnnotationConverter(cls);
                if (conv == null) {
                    throw new IllegalStateException("No registered converter found: " + cls);
                }
            }
            registered.putIfAbsent(cls, conv);
        }
        return conv;
    }

    /**
     * Finds the conversion method.
     * 
     * @param <T>  the type of the converter
     * @param cls  the class to find a method for, not null
     * @return the method to call, null means use {@code toString}
     */
    private <T> StringConverter<T> findAnnotationConverter(final Class<T> cls) {
        Method toString = findToStringMethod(cls);
        if (toString == null) {
            return null;
        }
        Constructor<T> con = findFromStringConstructor(cls);
        Method fromString = findFromStringMethod(cls, con == null);
        if (con == null && fromString == null) {
            throw new IllegalStateException("Class annotated with @ToString but not with @FromString");
        }
        if (con != null && fromString != null) {
            throw new IllegalStateException("Both method and constructor are annotated with @FromString");
        }
        if (con != null) {
            return new MethodConstructorStringConverter<T>(cls, toString, con);
        } else {
            return new MethodsStringConverter<T>(cls, toString, fromString);
        }
    }

    /**
     * Finds the conversion method.
     * 
     * @param cls  the class to find a method for, not null
     * @return the method to call, null means use {@code toString}
     */
    private Method findToStringMethod(Class<?> cls) {
        Method matched = null;
        Class<?> loopCls = cls;
        while (loopCls != null && matched == null) {
            Method[] methods = loopCls.getDeclaredMethods();
            for (Method method : methods) {
                ToString toString = method.getAnnotation(ToString.class);
                if (toString != null) {
                    if (matched != null) {
                        throw new IllegalStateException("Two methods are annotated with @ToString");
                    }
                    matched = method;
                }
            }
            loopCls = loopCls.getSuperclass();
        }
        return matched;
    }

    /**
     * Finds the conversion method.
     * 
     * @param <T>  the type of the converter
     * @param cls  the class to find a method for, not null
     * @return the method to call, null means use {@code toString}
     */
    private <T> Constructor<T> findFromStringConstructor(Class<T> cls) {
        try {
            Constructor<T> con = cls.getDeclaredConstructor(String.class);
            FromString fromString = con.getAnnotation(FromString.class);
            return fromString != null ? con : null;
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    /**
     * Finds the conversion method.
     * 
     * @param cls  the class to find a method for, not null
     * @return the method to call, null means use {@code toString}
     */
    private Method findFromStringMethod(Class<?> cls, boolean searchSuperclasses) {
        Method matched = null;
        Class<?> loopCls = cls;
        while (loopCls != null && matched == null) {
            Method[] methods = loopCls.getDeclaredMethods();
            for (Method method : methods) {
                FromString fromString = method.getAnnotation(FromString.class);
                if (fromString != null) {
                    if (matched != null) {
                        throw new IllegalStateException("Two methods are annotated with @ToString");
                    }
                    matched = method;
                }
            }
            if (searchSuperclasses == false) {
                break;
            }
            loopCls = loopCls.getSuperclass();
        }
        return matched;
    }

    //-----------------------------------------------------------------------
    /**
     * Registers a converter for a specific type.
     * <p>
     * The converter will be used for subclasses unless overidden.
     * <p>
     * No new converters may be registered for the global singleton.
     * 
     * @param <T>  the type of the converter
     * @param cls  the class to register a converter for, not null
     * @param converter  the String converter, not null
     */
    public <T> void register(final Class<T> cls, StringConverter<T> converter) {
        if (cls == null ) {
            throw new IllegalArgumentException("Class must not be null");
        }
        if (converter == null) {
            throw new IllegalArgumentException("StringConverter must not be null");
        }
        if (this == INSTANCE) {
            throw new IllegalStateException("Global singleton cannot be extended");
        }
        StringConverter<?> old = registered.putIfAbsent(cls, converter);
        if (old != null) {
            throw new IllegalStateException("Converter already registered for class: " + cls);
        }
    }

    //-----------------------------------------------------------------------
    /**
     * Returns a simple string representation of the object.
     * 
     * @return the string representation, never null
     */
    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
