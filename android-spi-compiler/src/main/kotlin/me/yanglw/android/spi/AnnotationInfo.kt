package me.yanglw.android.spi

/**
 * service provider 的 [ServiceProvider] 的信息载体。
 *
 * 主要用于优先级的排序。
 *
 * Created by yanglw on 2018/4/19.
 */
class AnnotationInfo(val className: String,
                     val services: Array<String>,
                     val priorities: IntArray,
                     val singleton: Boolean)