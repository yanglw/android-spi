package me.yanglw.android.spi

import com.android.build.api.transform.*
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import com.google.common.collect.ImmutableSet
import javassist.ClassPool
import javassist.CtConstructor
import javassist.Loader
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
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
class SpiTransform(private val project: Project, private val android: AppExtension) : Transform() {
  companion object {
    private const val SERVICE_REPOSITORY_CLASS_NAME: String = "me.yanglw.android.spi.ServiceRepository"
    private const val SERVICE_REPOSITORY_FIELD_NAME: String = "REPOSITORY"
  }

  private lateinit var pool: ClassPool

  /** ServiceRepository 类所在的 jar 文件。 */
  private lateinit var mRepositoryJarFile: File

  /** 所有添加 [ServiceProvider] 注解的类的集合。 */
  private val mAnnotationInfoList: MutableList<AnnotationInfo> = ArrayList()

  override fun getName(): String = "androidSpi"

  override fun getInputTypes(): Set<QualifiedContent.ContentType> = TransformManager.CONTENT_CLASS

  override fun getScopes(): MutableSet<QualifiedContent.Scope> = TransformManager.SCOPE_FULL_PROJECT

  override fun isIncremental(): Boolean = false

  @Throws(TransformException::class, InterruptedException::class, IOException::class)
  override fun transform(transformInvocation: TransformInvocation) {
    transformInvocation.outputProvider.deleteAll()

    pool = object : ClassPool(true) {
      override fun getClassLoader(): ClassLoader = Loader(this)
    }
    log("=====================load boot class path=====================")
    android.bootClasspath.forEach {
      log("load boot class path : ${it.absolutePath}")
      pool.appendClassPath(it.absolutePath)
    }

    loadAllClasses(transformInvocation.inputs, transformInvocation.outputProvider)

    generateServiceRepositoryClass(transformInvocation.outputProvider)
  }

  /** 加载项目所有的 jar 和 class 。 */
  private fun loadAllClasses(inputs: MutableCollection<TransformInput>, outputProvider: TransformOutputProvider) {
    log("=====================load all classes=====================")
    inputs.forEach {
      it.jarInputs.forEach { jar ->
        log("load jar : ${jar.file.absolutePath}")
        val outFile = outputProvider.getContentLocation(jar.name,
                                                        jar.contentTypes,
                                                        jar.scopes,
                                                        Format.JAR)
        addJar(jar.file, outFile)
      }

      it.directoryInputs.forEach { dir ->
        log("load file : ${dir.file.absolutePath}")
        val outDir = outputProvider.getContentLocation(dir.name,
                                                       dir.contentTypes,
                                                       dir.scopes,
                                                       Format.DIRECTORY)
        addFile(dir.file)
        if (dir.file.isDirectory) {
          FileUtils.copyDirectory(dir.file, outDir)
        } else {
          FileUtils.copyFile(dir.file, outDir)
        }
      }
    }
  }

  /** 增加 jar 。*/
  private fun addJar(file: File, outFile: File) {
    val zipFile = ZipFile(file)
    zipFile.stream()
        .filter { it.name.endsWith(".class", true) }
        .map { return@map pool.fromInputStream(zipFile.getInputStream(it)) }
        .peek { if (SERVICE_REPOSITORY_CLASS_NAME == it.name) mRepositoryJarFile = file }
        .map { it.getAnnotationInfo() }
        .filter { it != null }
        .forEach {
          log(it!!)
          mAnnotationInfoList.add(it)
        }

    if (!::mRepositoryJarFile.isInitialized || mRepositoryJarFile != file) {
      FileUtils.copyFile(file, outFile)
    }
  }

  /**
   * 添加 class 目录。将会递归遍历 class 文件和所有子文件夹。
   *
   * @param file 目标文件夹。
   */
  private fun addFile(file: File) {
    if (file.isDirectory) {
      file.listFiles()?.forEach { addFile(it) }
    } else {
      if (file.extension.equals("class", true)) {
        val clz = pool.fromInputStream(file.inputStream())

        val annotationInfo = clz.getAnnotationInfo()
        if (annotationInfo != null) {
          log(annotationInfo)
          mAnnotationInfoList.add(annotationInfo)
        }
      }
    }
  }

  /** 将所有的 service provider 信息写入 ServiceRepository 。 */
  private fun generateServiceRepositoryClass(outputProvider: TransformOutputProvider) {
    if (!::mRepositoryJarFile.isInitialized) {
      return
    }

    // 所有 service provider 类的集合，根据 service 进行分组。
    val providerMap: MutableMap<String, MutableList<ProviderInfo>> = HashMap()
    // 所有单例模式的 service provider 类的集合。
    val singletonSet: MutableSet<String> = TreeSet()

    // 对所有的 service provider 进行分组。
    for (annotation in mAnnotationInfoList) {
      for (k in annotation.services.indices) {
        val service: String = annotation.services[k]
        val list = providerMap.getOrPut(service) { mutableListOf() }
        if (list.find { it.name == annotation.className } == null) {
          list.add(ProviderInfo(annotation.className, annotation.priorities[k]))
        }
      }

      if (annotation.singleton) {
        singletonSet.add(annotation.className)
      }
    }

    log("=====================collect result:services list=====================")
    providerMap.forEach { (key, value) ->
      // 将 Provider 根据 priority 属性排序。
      value.sort()
      log(key)
      value.forEach { log("    ${it.name} --> priority = ${it.priority}") }
    }
    log("=====================collect result:singleton list=====================")
    singletonSet.forEach { log(it) }


    log("=====================generate source info=====================")

    val ctClass = pool.get(SERVICE_REPOSITORY_CLASS_NAME)
    val mapField = ctClass.getDeclaredField(SERVICE_REPOSITORY_FIELD_NAME)
    ctClass.removeField(mapField)
    ctClass.addField(mapField, "new java.util.HashMap(${providerMap.size})")
    log("$SERVICE_REPOSITORY_FIELD_NAME size = ${providerMap.size}")

    val singleMap = mutableMapOf<String, String>()

    var code: String
    val sb = StringBuilder("{")
    if (singletonSet.isNotEmpty()) {
      for ((i, name) in singletonSet.withIndex()) {
        val objectName = "object$i"
        code = "$name $objectName = new $name();"
        sb.append(code)
        log(code)

        singleMap[name] = objectName
      }
    }

    if (providerMap.isNotEmpty()) {
      code = "java.util.List list = null;"
      sb.append(code)
      log(code)

      providerMap.forEach { (key, value) ->
        if (value.isEmpty()) {
          return@forEach
        }
        code = "list = new java.util.LinkedList();"
        sb.append(code)
        log(code)

        code = "$SERVICE_REPOSITORY_FIELD_NAME.put($key.class, list);"
        sb.append(code)
        log(code)

        value.forEach {
          val singleObject = singleMap[it.name]
          if (singleObject != null) {
            code = "list.add($singleObject);"
            sb.append(code)
            log(code)
          } else {
            code = "list.add(${it.name}.class);"
            sb.append(code)
            log(code)
          }
        }
      }
    }
    sb.append('}')

    val staticConstructor: CtConstructor = ctClass.makeClassInitializer()
    staticConstructor.setBody(sb.toString())
    ctClass.writeFile(outputProvider.getContentLocation(SERVICE_REPOSITORY_CLASS_NAME,
                                                        TransformManager.CONTENT_CLASS,
                                                        ImmutableSet.of(QualifiedContent.Scope.PROJECT),
                                                        Format.DIRECTORY)
                          .absolutePath)
  }

  private fun log(text: String) {
    project.logger.info("$name -> $text")
  }

  private fun log(info: AnnotationInfo) {
    log("${info.className} --> singleton = ${info.singleton}")
    info.services.forEachIndexed { index, s ->
      log("    $s priority = ${info.priorities[index]}")
    }
  }
}
