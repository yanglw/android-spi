package me.yanglw.android.spi

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.google.common.collect.ImmutableSet
import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import javassist.Loader
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.annotation.*
import javassist.bytecode.annotation.Annotation
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.*
import java.util.zip.ZipFile

/**
 * android spi transform 。
 *
 * 在本类中，会遍历项目的 class 文件，从中获取添加有 [ServiceProvider] 注解的类，用以初始化 ServiceRepository.REPOSITORY 字段。
 *
 * Created by yanglw on 2018/4/19.
 *
 * @see Transform
 */
class SpiTransform : Transform() {
    companion object {
        private const val SERVICE_REPOSITORY_CLASS_NAME: String = "me.yanglw.android.spi.ServiceRepository"
        private const val SERVICE_REPOSITORY_FIELD_NAME: String = "REPOSITORY"
    }

    private var pool: ClassPool? = null

    /** ServiceRepository 类所在的 jar 文件。 */
    private var mRepositoryJarFile: File? = null

    /** 所有添加 [ServiceProvider] 注解的类的集合。 */
    private val mAllProviderSet: MutableList<ClassInfo> = ArrayList()

    override fun getName(): String {
        return "androidSpi"
    }

    override fun getInputTypes(): Set<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    override fun isIncremental(): Boolean {
        return false
    }

    @Throws(TransformException::class, InterruptedException::class, IOException::class)
    override fun transform(transformInvocation: TransformInvocation) {
        transformInvocation.outputProvider.deleteAll()

        pool = object : ClassPool(true) {
            override fun getClassLoader(): ClassLoader {
                return Loader(this)
            }
        }

        loadAllClasses(transformInvocation.inputs, transformInvocation.outputProvider)

        generateServiceRepositoryClass(transformInvocation.outputProvider)
    }

    /** 加载项目所有的 jar 和 class 。 */
    private fun loadAllClasses(inputs: MutableCollection<TransformInput>, outputProvider: TransformOutputProvider) {
        inputs.forEach {
            it.jarInputs.forEach { jar ->
                val outFile = outputProvider.getContentLocation(jar.name,
                                                                jar.contentTypes,
                                                                jar.scopes,
                                                                Format.JAR)
                addJar(jar.file, outFile)
            }

            it.directoryInputs.forEach { file ->
                val outDir = outputProvider.getContentLocation(file.name,
                                                               file.contentTypes,
                                                               file.scopes,
                                                               Format.DIRECTORY)
                addFile(file.file, file.file, outDir)
            }
        }
    }

    /** 增加 jar 。*/
    private fun addJar(file: File, outFile: File) {
        val zipFile = ZipFile(file)
        zipFile.stream()
                .filter {
                    it.name.endsWith(".class", true)
                }
                .map {
                    val inputStream = zipFile.getInputStream(it)
                    val clz = pool!!.makeClass(inputStream)
                    inputStream.close()
                    return@map clz
                }
                .peek {
                    if (SERVICE_REPOSITORY_CLASS_NAME == it.name) {
                        mRepositoryJarFile = file
                    }
                }
                .map { parserAnnotationInfo(it) }
                .filter { it != null }
                .map {
                    ClassInfo(it!!.className,
                              file, outFile,
                              it)
                }
                .forEach {
                    mAllProviderSet.add(it!!)
                }

        if (mRepositoryJarFile != file) {
            FileUtils.copyFile(file, outFile)
        }
    }

    /** 添加 class 或者 class 目录。 */
    private fun addFile(file: File, inputDir: File, outDir: File) {
        if (file.isDirectory) {
            FileUtils.copyDirectory(file, File(outDir, file.relativeTo(inputDir).path))
        } else {
            FileUtils.copyFile(file, File(outDir, file.relativeTo(inputDir).path))
        }

        loadClassFile(pool!!, file, inputDir, outDir)
    }

    /**
     * 递归遍历 class 文件和文件夹。
     *
     * @param file 目标文件/文件夹。
     * @param inputDir 目标文件/文件夹的起始目录。
     * @param outDir 目标文件/文件夹的输入目录的起始目录。
     */
    private fun loadClassFile(pool: ClassPool, file: File, inputDir: File, outDir: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                loadClassFile(pool, it, inputDir, outDir)
            }
        } else {
            if (file.extension.equals("class", true)) {
                val inputStream = file.inputStream()
                val clz = pool.makeClass(inputStream)
                inputStream.close()
                val outFile = File(outDir, file.relativeTo(inputDir).path)
                val annotationInfo = parserAnnotationInfo(clz)
                if (annotationInfo != null) {
                    mAllProviderSet.add(ClassInfo(annotationInfo.className,
                                                  file,
                                                  outFile,
                                                  annotationInfo))
                }
            }
        }
    }

    /**
     * 从 CtClass 中获取 [ServiceProvider] 注解的信息。
     *
     * @return 若该 CtClass 有 [ServiceProvider] 注解，则返回 [ServiceProvider] 的信息，否则返回 null 。
     *
     * @throws IllegalArgumentException 若 CtClass 有 [ServiceProvider] 注解，但是 [ServiceProvider.services] 为 null 或者空数组。
     */
    private fun parserAnnotationInfo(clazz: CtClass): AnnotationInfo? {
        val classFile = clazz.classFile
        var annotation: Annotation? = null

        var attribute = classFile.getAttribute(AnnotationsAttribute.visibleTag)
        if (attribute != null && attribute is AnnotationsAttribute) {
            annotation = attribute.getAnnotation(ServiceProvider::class.java.name)
        } else {
            attribute = classFile.getAttribute(AnnotationsAttribute.invisibleTag)
            if (attribute != null && attribute is AnnotationsAttribute) {
                annotation = attribute.getAnnotation(ServiceProvider::class.java.name)
            }
        }

        if (annotation == null) {
            return null
        }

        // 获取 services 的值。
        val servicesMemberValue = annotation.getMemberValue(
                ServiceProvider::services.name) as ArrayMemberValue
        if (servicesMemberValue.value.isEmpty()) {
            throw IllegalArgumentException("services is null or empty")
        }
        val services: MutableList<String> = ArrayList()
        servicesMemberValue.value.forEach {
            if (it is ClassMemberValue) {
                services.add(it.value)
            }
        }

        // 获取 priorities 的值。
        val prioritiesMemberValue = annotation.getMemberValue(
                ServiceProvider::priorities.name) as? ArrayMemberValue
        val prioritiesValues = if (prioritiesMemberValue == null || prioritiesMemberValue.value.isEmpty()) {
            IntArray(services.size)
        } else {
            prioritiesMemberValue.value
                    .map {
                        (it as? IntegerMemberValue)?.value ?: 0
                    }
                    .toIntArray()
        }
        val priorities = IntArray(services.size)
        System.arraycopy(prioritiesValues, 0,
                         priorities, 0,
                         if (services.size > prioritiesValues.size) prioritiesValues.size else services.size)

        // 获取 singleton 的值。
        val singletonMemberValue = annotation.getMemberValue(
                ServiceProvider::singleton.name) as? BooleanMemberValue
        val singleton = singletonMemberValue?.value ?: false
        return AnnotationInfo(clazz.name, services.toTypedArray(), priorities, singleton)
    }

    /** 将所有的 service provider 信息写入 ServiceRepository 。 */
    private fun generateServiceRepositoryClass(outputProvider: TransformOutputProvider) {
        if (mRepositoryJarFile == null) {
            return
        }

        // 所有 service provider 类的集合，根据 service 进行分组，同时根据 priorities 进行了排序。
       val providerMap:  MutableMap<String, MutableList<ProviderInfo>> = HashMap()
        // 所有单例模式的 service provider 类的集合。
       val singletonSet: MutableSet<String> = TreeSet()

        // 对所有的 service provider 进行分组。
        for (i in mAllProviderSet.indices) {
            val classInfo = mAllProviderSet[i]
            val annotation = classInfo.annotation
            for (k in annotation.services.indices) {
                val service: String = annotation.services[k]
                var list: MutableList<ProviderInfo>? = providerMap[service]
                if (list == null) {
                    list = LinkedList()
                    providerMap[service] = list
                }
                if (list.find { it.name == classInfo.name } == null) {
                    list.add(ProviderInfo(classInfo.name, annotation.priorities[k]))
                }
            }
            if (annotation.singleton) {
                singletonSet.add(classInfo.name)
            }
        }

        // 对 service provider 进行降序排序。
        providerMap.forEach {
            it.value.sortDescending()
        }

        val ctClass = pool!!.get(SERVICE_REPOSITORY_CLASS_NAME)
        val mapField = ctClass.getField(SERVICE_REPOSITORY_FIELD_NAME)
        ctClass.removeField(mapField)
        ctClass.addField(mapField, "new java.util.HashMap(${providerMap.size})")

        val singleMap = TreeMap<String, String>()

        val sb = StringBuilder("{")
        if (singletonSet.isNotEmpty()) {
            for ((i, name) in singletonSet.withIndex()) {
                val objectName = "object$i"
                sb.append("$name $objectName = new $name();")
                singleMap[name] = objectName
            }
        }
        if (providerMap.isNotEmpty()) {
            sb.append("java.util.List list = null;")
            providerMap.forEach { key, value ->
                if (value.isEmpty()) {
                    return@forEach
                }
                sb.append("list = new java.util.LinkedList();")
                sb.append("$SERVICE_REPOSITORY_FIELD_NAME.put($key.class, list);")
                value.forEach {
                    val singleObject = singleMap[it.name]
                    if (singleObject != null) {
                        sb.append("list.add($singleObject);")
                    } else {
                        sb.append("list.add(${it.name}.class);")
                    }
                }
            }
        }
        sb.append('}')

        var staticConstructor: CtConstructor? = ctClass.classInitializer
        if (staticConstructor == null) {
            staticConstructor = ctClass.makeClassInitializer()
            ctClass.addConstructor(staticConstructor)
        }
        staticConstructor!!.setBody(sb.toString())
        ctClass.writeFile(outputProvider.getContentLocation(SERVICE_REPOSITORY_CLASS_NAME,
                                                            TransformManager.CONTENT_CLASS,
                                                            ImmutableSet.of(QualifiedContent.Scope.PROJECT),
                                                            Format.DIRECTORY)
                                  .absolutePath)
    }
}

