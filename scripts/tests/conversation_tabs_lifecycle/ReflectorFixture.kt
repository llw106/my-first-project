package h.Hchat.utils

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object KavaReflector {
    fun loadClass(name: String, loader: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, loader) }.getOrNull()
    fun findMethodRecursive(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { return it }
            current = current.superclass
        }
        return null
    }
    fun declaredFields(clazz: Class<*>) = clazz.declaredFields.toList()
    fun isStatic(field: Field) = Modifier.isStatic(field.modifiers)
    fun readField(field: Field, owner: Any): Any? { field.isAccessible = true; return field.get(owner) }
}
object HLog {
    val errors = arrayListOf<String>()
    fun e(message: String) { errors.add(message) }
    fun e(message: String, error: Throwable) { errors.add(message + ": " + error.message) }
}
