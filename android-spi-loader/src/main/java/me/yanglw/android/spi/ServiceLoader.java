package me.yanglw.android.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;

/**
 * <p>一个类似于 {@link java.util.ServiceLoader} 的 Service Provider 的加载器。</p>
 *
 * Created by yanglw on 2018/4/19.
 *
 * @param <S> 所要获取的 Service Provider 的 service 类型。
 *
 * @see java.util.ServiceLoader
 */
final public class ServiceLoader<S> implements Iterable<S> {
    private final Class<S> mService;
    private final List<S> mProviders;

    private ServiceLoader(Class<S> service) {
        mService = service;
        List<?> list = ServiceRepository.get(mService);
        if (list.isEmpty()) {
            mProviders = Collections.emptyList();
            return;
        }

        mProviders = new ArrayList<>(list.size());
        for (Object item : list) {
            Object obj;
            if (item instanceof Class) {
                Class clz = (Class) item;
                try {
                    obj = clz.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new ServiceConfigurationError(clz.getName() + " could not be initialized", e);
                }
            } else {
                obj = item;
            }

            S provider;
            try {
                provider = mService.cast(obj);
            } catch (ClassCastException e) {
                throw new ServiceConfigurationError(
                        obj.getClass().getName() + " can not cast to " + this.mService.getName(),
                        e);
            }

            mProviders.add(provider);
        }
    }

    public List<S> list() {
        return Collections.unmodifiableList(mProviders);
    }

    @Override
    public Iterator<S> iterator() {
        return list().iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ServiceLoader<?> that = (ServiceLoader<?>) o;
        return Objects.equals(mService, that.mService);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mService);
    }

    /**
     * 获取指定类型 service 的 ServiceLoader 实例。
     *
     * @param service 所要获取的 service 类型的 Class 对象。
     * @param <S> 所要获取的 Service Provider 的 service 类型。
     *
     * @return 一个新的只存储指定 service 的 ServiceLoader 实例对象。
     *
     * @throws NullPointerException 若 service 为 null ，则抛出空指针异常。
     */
    public static <S> ServiceLoader<S> load(Class<S> service) {
        if (service == null) {
            throw new NullPointerException("service can not be null");
        }

        return new ServiceLoader<>(service);
    }
}
