package me.yanglw.android.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Service Provider 的标记注解。</p>
 *
 * Created by yanglw on 2018/4/19.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ServiceProvider {
    /** 当前类所支持的 service 数组。 */
    Class[] services();

    /**
     * 当前类所支持的 service 的优先级。
     *
     * <p>数值越大，优先级越高。</p>
     *
     * <p>数组的长度将跟随 {@link #services()} 的长度。
     * <ul>
     * <li>若数组的长度超过 {@link #services()} 的长度，则丢弃多余的部分。 </li>
     * <li>若数组的长度少于 {@link #services()} 的长度，则补充长度，且默认值为 0 。 </li>
     * </ul>
     * </p>
     */
    int[] priorities() default {};

    /** 标记当前类是否是单例类。默认不是单例。 */
    boolean singleton() default false;
}