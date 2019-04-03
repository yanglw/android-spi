package me.yanglw.android.spi

import javassist.ClassPool
import javassist.CtClass
import javassist.Modifier
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.annotation.*
import javassist.bytecode.annotation.Annotation
import java.io.InputStream

internal fun ClassPool.fromInputStream(inputStream: InputStream): CtClass {
  val clz = makeClass(inputStream)
  inputStream.close()
  return clz
}

internal fun CtClass.getAnnotation(): Annotation? {
  val attribute = classFile.getAttribute(AnnotationsAttribute.invisibleTag)
  return if (attribute != null && attribute is AnnotationsAttribute)
    attribute.getAnnotation(ServiceProvider::class.java.name)
  else null
}

/**
 * 从 CtClass 中获取 [ServiceProvider] 注解的信息。
 *
 * @return 若该 CtClass 有 [ServiceProvider] 注解，则返回 [ServiceProvider] 的信息，否则返回 null 。
 *
 * @throws IllegalArgumentException 若 CtClass 有 [ServiceProvider] 注解，但是 [ServiceProvider.services] 为 null 或者空数组。
 */
internal fun CtClass.getAnnotationInfo(): AnnotationInfo? {
  if (simpleName.contains(Regex("^R\$|^R\\\$\\w+\$|^BuildConfig\$"))) {
    return null
  }

  if (Modifier.isAbstract(modifiers)
      || Modifier.isInterface(modifiers)
      || !Modifier.isPublic(modifiers)
      || isAnnotation
      || isEnum
      || isPrimitive) {
    return null
  }

  val annotation = getAnnotation() ?: return null
  // 获取 services 的值。
  val servicesMemberValue = annotation.getMemberValue(ServiceProvider::services.name) as ArrayMemberValue
  if (servicesMemberValue.value.isEmpty()) {
    throw IllegalArgumentException("services is null or empty")
  }

  val services = servicesMemberValue
      .value
      .filter { it is ClassMemberValue }
      .map { (it as ClassMemberValue).value }
      .toList()
      .toTypedArray()

  // 获取 priorities 的值。
  val prioritiesMemberValue = annotation.getMemberValue(ServiceProvider::priorities.name) as? ArrayMemberValue
  val prioritiesValues = if (prioritiesMemberValue == null || prioritiesMemberValue.value.isEmpty()) {
    IntArray(services.size)
  } else {
    prioritiesMemberValue
        .value
        .filter { it is IntegerMemberValue }
        .map { (it as IntegerMemberValue).value }
        .toIntArray()
  }
  val priorities = IntArray(services.size)
  System.arraycopy(prioritiesValues, 0,
                   priorities, 0,
                   if (services.size > prioritiesValues.size) prioritiesValues.size else services.size)

  // 获取 singleton 的值。
  val singletonMemberValue = annotation.getMemberValue(ServiceProvider::singleton.name) as? BooleanMemberValue
  val singleton = singletonMemberValue?.value ?: false
  return AnnotationInfo(name, services, priorities, singleton)
}

class A(private val num: Int) : Comparable<A> {
  override fun compareTo(other: A): Int = other.num - num
  override fun toString(): String {
    return "A(num=$num)"
  }

}

fun main(args: Array<String>) {
  sortedSetOf<A>(A(4),A(1),A(5),A(-1)).forEach { println(it) }
}