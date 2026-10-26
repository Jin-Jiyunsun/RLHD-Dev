package rs117.hd.dev;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Reaching into 117 HD's private members, and making stand-ins for the client's objects, which the developer tools need
 * to work without changes to 117 HD
 */
public class Reflection {
	// What a stand-in's answers return for a method which should return null, since null leaves it unanswered
	public static final Object NULL = new Object();

	/**
	 * A private field, made accessible
	 */
	public static Field field(Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException ex) {
			throw new IllegalStateException("117 HD no longer has " + type.getSimpleName() + "." + name, ex);
		}
	}

	/**
	 * A private method, made accessible
	 */
	public static Method method(Class<?> type, String name, Class<?>... parameters) {
		try {
			Method method = type.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method;
		} catch (NoSuchMethodException ex) {
			throw new IllegalStateException("117 HD no longer has " + type.getSimpleName() + "." + name, ex);
		}
	}

	public static Object get(Field field, @Nullable Object owner) {
		try {
			return field.get(owner);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A stand-in for an interface which answers the methods the function knows by name, and returns nothing for the rest.
	 * The function returns null for methods it doesn't know.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T stub(Class<T> type, Function<String, Object> answers) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "equals":
					return proxy == args[0];
				case "hashCode":
					return System.identityHashCode(proxy);
			}
			Object answer = answers.apply(method.getName());
			if (answer == NULL)
				return null;
			return answer != null ? answer : emptyValue(method.getReturnType());
		});
	}

	/**
	 * A stand-in for an object which answers the methods the function knows by name, and passes the rest on to the object.
	 * The function returns null for methods it leaves to the object.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T wrap(Class<T> type, T target, Function<String, Object> answers) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			Object answer = answers.apply(method.getName());
			if (answer == NULL)
				return null;
			if (answer != null)
				return answer;
			try {
				return method.invoke(target, args);
			} catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
		});
	}

	/**
	 * Zero, false or null, whichever the type takes
	 */
	@Nullable
	private static Object emptyValue(Class<?> type) {
		if (!type.isPrimitive() || type == void.class)
			return null;
		if (type == boolean.class)
			return false;
		if (type == char.class)
			return (char) 0;
		if (type == byte.class)
			return (byte) 0;
		if (type == short.class)
			return (short) 0;
		if (type == long.class)
			return 0L;
		if (type == float.class)
			return 0f;
		if (type == double.class)
			return 0d;
		return 0;
	}
}
