package me.yanglw.android.spi;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * <p>Service Provider 的存储仓库，所有通过 {@code ServiceProvider} 获取的类都将存储在此类中。</p>
 *
 * <p>在编译阶段，通过查找 {@code ServiceProvider} 注解，将所有的 Service Provider 类注册入 {@link #REPOSITORY} 中。</p>
 *
 * Created by yanglw on 2018/4/19.
 */
class ServiceRepository {
    /** <p>Service Provider 的存储容器。</p> */
    private static final HashMap<Class<?>, List<?>> REPOSITORY = new HashMap<>();

    private ServiceRepository() {}

    /**
     * 获取指定的 service 的 Service Provider 列表。
     *
     * @param service service 类。
     *
     * @return 如果存在该 service 的 Service Provider 则返回 Service Provider 列表，否则返回一个空列表。
     */
    static List<?> get(Class<?> service) {
        List<?> list = REPOSITORY.get(service);
        if (list == null) {
            return Collections.emptyList();
        } else {
            return Collections.unmodifiableList(list);
        }
    }
}