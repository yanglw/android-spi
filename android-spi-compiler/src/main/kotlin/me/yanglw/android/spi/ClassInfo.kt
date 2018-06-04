package me.yanglw.android.spi

import java.io.File

/**
 * service provider 的信息载体。
 *
 * Created by yanglw on 2018/4/19.
 */
class ClassInfo(
        /** service provider 的类名。 */
        val name: String,
        /** service provider 的来源文件。 */
        val from: File,
        /** service provider 的输出文件。 */
        val out: File,
        /** service provider 的[ServiceProvider] 的信息。 */
        val annotation: AnnotationInfo) : Comparable<ClassInfo> {

    override fun compareTo(other: ClassInfo): Int {
        return name.compareTo(other.name)
    }
}