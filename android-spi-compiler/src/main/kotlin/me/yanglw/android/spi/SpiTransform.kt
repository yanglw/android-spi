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
import kotlin.collections.HashMap
import kotlin.collections.LinkedHashSet

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

    /** 项目依赖的所有的 jar 文件集合，key 为 jar 入口文件，value 为 jar 输出文件。 */
    private val mInputJarMap = HashMap<File, File>()
    /** 项目的 class 文件集合，key 为 jar 入口文件，value 为 jar 输出文件。 */
    private val mInputClassMap = HashMap<File, File>()
    /** 所有添加 [ServiceProvider] 注解的类的集合。 */
    private val mAllProviderSet: MutableList<ClassInfo> = ArrayList()
    /** 所有 service provider 类的集合，根据 service 进行分组，同时根据 priorities 进行了排序。 */
    private val mProviderMap: MutableMap<String, MutableList<ProviderInfo>> = HashMap()
    /** 所有单例模式的 service provider 类的集合。 */
    private val mSingletonSet: MutableSet<String> = TreeSet()

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
        return true
    }

    override fun transform(context: Context?, inputs: MutableCollection<TransformInput>?,
                           referencedInputs: MutableCollection<TransformInput>?,
                           outputProvider: TransformOutputProvider?, isIncremental: Boolean) {
        if (inputs == null || outputProvider == null) {
            return
        }

        pool = object : ClassPool(true) {
            override fun getClassLoader(): ClassLoader {
                return Loader(this)
            }
        }
        if (isIncremental) {
            outputProvider.deleteAll()
            mInputClassMap.clear()
            mInputJarMap.clear()
            mAllProviderSet.clear()
        }
        loadAllClasses(inputs, outputProvider)
        generateServiceRepositoryClass(outputProvider)
        pool = null
    }

    @Throws(TransformException::class, InterruptedException::class, IOException::class)
    override fun transform(transformInvocation: TransformInvocation) {
        pool = object : ClassPool(true) {
            override fun getClassLoader(): ClassLoader {
                return Loader(this)
            }
        }
        if (!transformInvocation.isIncremental) {
            transformInvocation.outputProvider.deleteAll()
            mInputClassMap.clear()
            mInputJarMap.clear()
            mAllProviderSet.clear()
        }
        loadAllClasses(transformInvocation.inputs, transformInvocation.outputProvider)
        generateServiceRepositoryClass(transformInvocation.outputProvider)
        pool = null
    }

    /** 加载项目所有的 jar 和 class 。 */
    private fun loadAllClasses(inputs: MutableCollection<TransformInput>, outputProvider: TransformOutputProvider) {
        // 项目通过 maven 依赖 jar 时，删除依赖后，jarInputs 不会有删除信息，需要自己排除。
        // 因此需要记录上一次所有输入的 jar 和本次所有输入的 jar ，若上次输入的 jar 没有出现在本次输入的 jar ，则说明该 jar 被删除了。
        val allJars = LinkedHashSet<File>()
        inputs.forEach {
            it.jarInputs.forEach jarForEach@{
                // 获取 jar 的输出文件。
                val outFile = outputProvider.getContentLocation(it.name,
                                                                it.contentTypes,
                                                                it.scopes,
                                                                Format.JAR)
                when (it.status) {
                    Status.ADDED, Status.NOTCHANGED -> {
                        allJars.add(it.file)
                        addJar(it.file, outFile)
                    }
                    Status.REMOVED -> {
                        removeJar(it.file)
                    }
                    Status.CHANGED -> {
                        allJars.add(it.file)
                        addJar(it.file, outFile)
                        removeJar(it.file)
                    }
                    else ->
                        return@jarForEach
                }
            }

            it.directoryInputs.forEach { item ->
                val inputDir = item.file
                val outDir = outputProvider.getContentLocation(item.name,
                                                               item.contentTypes,
                                                               item.scopes,
                                                               Format.DIRECTORY)
                val map = item.changedFiles
                if (map == null || map.isEmpty()) {
                    addFile(inputDir, inputDir, outDir)
                } else {
                    map.forEach mapForEach@{ entry ->
                        when (entry.value) {
                            Status.ADDED ->
                                addFile(entry.key, inputDir, outDir)
                            Status.REMOVED -> {
                                removeFile(entry.key)
                            }
                            Status.CHANGED -> {
                                removeFile(entry.key)
                                addFile(entry.key, inputDir, outDir)
                            }
                            else ->
                                return@mapForEach
                        }
                    }
                }
            }
        }
        // 删除上次输入的且没有在本次输入的 jar 。
        removeNotExistsJar(allJars)
    }

    /** 增加 jar 。*/
    private fun addJar(file: File, outFile: File) {
        mInputJarMap[file] = outFile

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

    /** 删除 jar 。*/
    private fun removeJar(file: File) {
        val outFile2 = mInputJarMap.remove(file)
        if (outFile2 != null && outFile2.exists()) {
            outFile2.delete()
        }

        mAllProviderSet.removeIf {
            it.from == file
        }
    }

    /**
     * 删除上次输入的且没有在本次输入的 jar 。
     *
     * 项目通过 maven 依赖 jar 时，删除依赖后，jarInputs 不会有删除信息，需要自己排除。
     * 因此需要记录上一次所有输入的 jar 和本次所有输入的 jar ，若上次输入的 jar 没有出现在本次输入的 jar ，则说明该 jar 被删除了。
     */
    private fun removeNotExistsJar(inputJars: Collection<File>) {
        mInputJarMap.filter { entry ->
            !inputJars.any {
                it == entry.key
            }
        }.forEach {
            removeJar(it.key)
        }
    }

    private fun addFile(file: File, inputDir: File, outDir: File) {
        if (file.isDirectory) {
            FileUtils.copyDirectory(file, File(outDir, file.relativeTo(inputDir).path))
        } else {
            FileUtils.copyFile(file, File(outDir, file.relativeTo(inputDir).path))
        }

        loadClassPath(pool!!, file, inputDir, outDir)
    }

    /**
     * 递归遍历 class 文件和文件夹。
     *
     * @param file 目标文件/文件夹。
     * @param inputDir 目标文件/文件夹的起始目录。
     * @param outDir 目标文件/文件夹的输入目录的起始目录。
     */
    private fun loadClassPath(pool: ClassPool, file: File, inputDir: File, outDir: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                loadClassPath(pool, it, inputDir, outDir)
            }
        } else {
            if (file.extension.equals("class", true) && !mInputClassMap.containsKey(file)) {
                val inputStream = file.inputStream()
                val clz = pool.makeClass(inputStream)
                inputStream.close()
                val outFile = File(outDir, file.relativeTo(inputDir).path)
                mInputClassMap[file] = outFile
                sequenceOf(clz)
                        .map { parserAnnotationInfo(it) }
                        .filter { it != null }
                        .map {
                            ClassInfo(it!!.className,
                                      file,
                                      outFile,
                                      it)
                        }
                        .forEach {
                            mAllProviderSet.add(it)
                        }
            }
        }
    }

    /** 删除 class 文件。 */
    private fun removeFile(file: File) {
        val outFile = mInputClassMap.remove(file) ?: return
        if (outFile.exists()) {
            outFile.delete()
        }
        mAllProviderSet.removeIf {
            it.from == file
        }
    }

    /** 将所有的 service provider 信息写入 ServiceRepository 。 */
    private fun generateServiceRepositoryClass(outputProvider: TransformOutputProvider) {
        if (mRepositoryJarFile == null) {
            return
        }

        mProviderMap.clear()
        mSingletonSet.clear()

        // 对所有的 service provider 进行分组。
        for (i in mAllProviderSet.indices) {
            val classInfo = mAllProviderSet[i]
            val annotation = classInfo.annotation
            for (k in annotation.services.indices) {
                val service: String = annotation.services[k]
                var list: MutableList<ProviderInfo>? = mProviderMap[service]
                if (list == null) {
                    list = LinkedList()
                    mProviderMap[service] = list
                }
                if (list.find { it.name == classInfo.name } == null) {
                    list.add(ProviderInfo(classInfo.name, annotation.priorities[k]))
                }
            }
            if (annotation.singleton) {
                mSingletonSet.add(classInfo.name)
            }
        }

        // 对 service provider 进行降序排序。
        mProviderMap.forEach {
            it.value.sortDescending()
        }

        val ctClass = pool!!.get(SERVICE_REPOSITORY_CLASS_NAME)
        val mapField = ctClass.getField(SERVICE_REPOSITORY_FIELD_NAME)
        ctClass.removeField(mapField)
        ctClass.addField(mapField, "new java.util.HashMap(${mProviderMap.size})")

        val singleMap = TreeMap<String, String>()

        val sb = StringBuilder("{")
        if (mSingletonSet.isNotEmpty()) {
            for ((i, name) in mSingletonSet.withIndex()) {
                val objectName = "object$i"
                sb.append("$name $objectName = new $name();")
                singleMap[name] = objectName
            }
        }
        if (mProviderMap.isNotEmpty()) {
            sb.append("java.util.List list = null;")
            mProviderMap.forEach { key, value ->
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
        println(sb.toString())
        staticConstructor!!.setBody(sb.toString())
        ctClass.writeFile(outputProvider.getContentLocation(SERVICE_REPOSITORY_CLASS_NAME,
                                                            TransformManager.CONTENT_CLASS,
                                                            ImmutableSet.of(QualifiedContent.Scope.PROJECT),
                                                            Format.DIRECTORY)
                                  .absolutePath)
    }
}

