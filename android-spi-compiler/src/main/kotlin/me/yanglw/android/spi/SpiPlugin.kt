package me.yanglw.android.spi

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * <p>android spi plugin 入口。</p>
 *
 * Created by yanglw on 2018/4/19.
 */
class SpiPlugin : Plugin<Project> {
    override fun apply(target: Project?) {
        if (target == null) {
            return
        }
        if (!target.plugins.hasPlugin("com.android.application")) {
            throw NullPointerException("not find com.android.application plugin.")
        }
        val android: AppExtension = target.extensions.findByName("android") as AppExtension
        android.registerTransform(SpiTransform(target, android))
    }
}