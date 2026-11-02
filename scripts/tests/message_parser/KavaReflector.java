package h.Hchat.utils;

import java.lang.reflect.Field;

public final class KavaReflector {
    public static Field findFieldRecursive(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    public static Object readField(Field field, Object owner) throws IllegalAccessException {
        return field.get(owner);
    }

    public static Field[] declaredFields(Class<?> type) {
        return type.getDeclaredFields();
    }
}
