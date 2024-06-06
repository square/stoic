package com.square.stoic.reflect

import com.square.stoic.Stoic
import com.square.stoic.helpers.*
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

fun inspect(clazz: Class<*>) {
  // Note: technically I should walk up the superclasses to find other declared methods
  eprintln("Inspecting ${clazz.name}")
  val methods = (clazz.declaredMethods.asList() + clazz.methods.asList()).distinct().sortedBy { it.name }
  val fields = (clazz.declaredFields.asList() + clazz.fields.asList()).distinct().sortedBy { it.name }
  fields.forEach {
    eprintln("${it.name} : ${it.type}")
  }
  methods.forEach {
    eprintln("fun ${it.name}(${it.parameterTypes.asList()})")
  }
  eprintln("Done")
}

// Reflection helpers
val classLoader: ClassLoader = Stoic::class.java.classLoader!!

class LruCache<K, V>(private val cacheSize: Int) : LinkedHashMap<K, V>(cacheSize, 0.75f, true) {
  override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean {
    // Remove the eldest entry if the size exceeds the predefined cache size
    return size > cacheSize
  }
}

val Class<*>.sm: MagicMethods
  get() {
  return MagicMethods(null, this)
}

val Class<*>.sf: MagicFields
  get() {
  return MagicFields(null, this)
}

val Any.m: MagicMethods
  get() {
  return MagicMethods(this, this.javaClass)
}

val Any.f: MagicFields
  get() {
  return MagicFields(this, this.javaClass)
}

class c {
  companion object {
    operator fun get(name: String): Class<*> {
      return classLoader.loadClass(name)
    }
  }
}

class MagicMethod(private val obj: Any?, private val method: Method) {
  operator fun invoke(vararg params: Any?): Any? {
    try {
      return method.invoke(obj, *params)
    } catch (e: InvocationTargetException) {
      throw e.targetException
    } catch (e: ReflectiveOperationException) {
      // TODO: if sig wasn't specified we might incorrectly resolve against a method in the base class
      // when a method in the superclass was intended - it'd be nice to check for that
      // TODO: also catch if there is another sig that would have worked

      // We catch and rethrow for a slightly less confusing stacktrace
      throw Exception(e.message)
    }
  }
}

class MagicMethods(private val obj: Any?, private val clazz: Class<*>) {
  operator fun get(name: String, jvmSig: String? = null): MagicMethod {
    val key = Triple(clazz, name, jvmSig)
    val method = cache[key]
    val resolvedMethod = if (method == null) {
      val resolvedMethod = try {
        resolve(clazz, name, jvmSig)
      } catch (e: NoSuchMethodException) {
        //eprintln("clazz: ${clazz.name}")
        //for (m in clazz.declaredMethods.sortedBy { it.name }) {
        //  eprintln("method: ${m.name} ${m.modifiers} ${m.parameterTypes.asList()}")
        //}
        throw NoSuchMethodException("$clazz $name $jvmSig not found")
      }

      cache[key] = resolvedMethod
      resolvedMethod
    } else {
      method
    }

    return MagicMethod(obj, resolvedMethod)
  }

  companion object {
    private val cache = LruCache<Triple<Class<*>, String, String?>, Method>(1024)

    private fun resolve(clazz: Class<*>, name: String, jvmSig: String?): Method {
      val resolved = if (jvmSig != null) {
        resolve(clazz, name, jvmSigToClassArray(jvmSig))
      } else {
        resolve(clazz, name)
      }

      resolved.isAccessible = true

      return resolved
    }

    private fun resolve(clazz: Class<*>, name: String): Method {
      val candidates = clazz.declaredMethods.filter { it.name == name }
      if (candidates.size == 1) {
        return candidates[0]
      } else if (candidates.size > 1) {
        // TODO: also consider superclass candidates
        val sigs = candidates.map { "\"" + classArrayToJvmSig(it.parameterTypes) + "\"" }
        throw Exception("method[$name] - use method[$name, sig] syntax, where sig is any of $sigs")
      } else {
        if (clazz.superclass != null) {
          return resolve(clazz.superclass, name, null)
        } else {
          throw NoSuchMethodException()
        }
      }
    }

    private fun resolve(clazz: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method {
      return try {
        clazz.getDeclaredMethod(name, *parameterTypes)
      } catch (e: NoSuchMethodException) {
        if (clazz.superclass != null) {
          resolve(clazz.superclass, name, parameterTypes)
        } else {
          throw e
        }
      }
    }

    private fun classArrayToJvmSig(classes: Array<Class<*>>): String {
      val sb = StringBuilder()
      for (clazz in classes) {
        sb.append(getClassDescriptor(clazz))
      }
      return sb.toString()
    }

    private fun jvmSigToClassArray(signature: String): Array<Class<*>> {
      val classes = mutableListOf<Class<*>>()
      var i = 0
      while (i < signature.length) {
        when (signature[i]) {
          'B' -> classes.add(Byte::class.java)
          'C' -> classes.add(Char::class.java)
          'D' -> classes.add(Double::class.java)
          'F' -> classes.add(Float::class.java)
          'I' -> classes.add(Int::class.java)
          'J' -> classes.add(Long::class.java)
          'S' -> classes.add(Short::class.java)
          'Z' -> classes.add(Boolean::class.java)
          'L' -> {
            // Object types (e.g., "Ljava/lang/String;")
            val end = signature.indexOf(';', i)
            val type = signature.substring(i + 1, end).replace('/', '.')
            classes.add(Class.forName(type))
            i = end // Move index to end of class descriptor
          }
          '[' -> {
            // Arrays (e.g., "[Ljava/lang/String;")
            var count = 1
            while (signature[i + count] == '[') {
              count++ // Count dimensions
            }
            var typeDescriptor = signature.substring(i, i + count + 1) // Include next character after '[' series
            if (signature[i + count] == 'L') {
              val end = signature.indexOf(';', i)
              typeDescriptor = signature.substring(i, end + 1)
              i = end // Move index to end of class descriptor
            }
            classes.add(Class.forName(typeDescriptor))
          }
        }
        i++
      }
      return classes.toTypedArray()
    }

    private fun getClassDescriptor(clazz: Class<*>): String {
      if (clazz.isArray) {
        return clazz.name.replace('.', '/')
      }
      return when (clazz) {
        Byte::class.java -> "B"
        Char::class.java -> "C"
        Double::class.java -> "D"
        Float::class.java -> "F"
        Int::class.java -> "I"
        Long::class.java -> "J"
        Short::class.java -> "S"
        Boolean::class.java -> "Z"
        Void.TYPE -> "V"
        else -> "L${clazz.name.replace('.', '/')};"  // Ljava/lang/String; for example
      }
    }

  }
}

class MagicFields(private val obj: Any?, private val clazz: Class<*>) {

  operator fun get(name: String, className: String? = null): Any? {
    return lookup(clazz, name, className).get(obj)
  }

  operator fun set(name: String, className: String? = null, value: Any?) {
    lookup(clazz, name, className).set(obj, value)
  }

  companion object {
    private val cache = LruCache<Triple<Class<*>, String, String?>, Field>(1024)

    private fun lookup(clazz: Class<*>, name: String, className: String?): Field {
      val key = Triple(clazz, name, className)
      val field = cache[key]
      return if (field == null) {
        val resolvedField = resolve(clazz, name, className)
        resolvedField.isAccessible = true
        cache[key] = resolvedField
        resolvedField
      } else {
        field
      }
    }

    private fun resolve(clazz: Class<*>, name: String, className: String?): Field {
      if (className == null) {
        try {
          return resolve(clazz, name)
        } catch (e: NoSuchFieldException) {
          // Re-throw with the derived-most class name
          throw NoSuchFieldException("No field $name on $clazz")
        }
      } else {
        val s = classLoader.loadClass(className)
        assert(s.isAssignableFrom(clazz))
        return s.getDeclaredField(name)
      }
    }

    private fun resolve(clazz: Class<*>, name: String): Field {
      return try {
        clazz.getDeclaredField(name)
      } catch (e: NoSuchFieldException) {
        resolve(clazz.superclass ?: throw e, name)
      }
    }
  }
}

