# Android SPI

这是一个类似于 [Java SPI](https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html) 的库，用于支持获取目标实现类。与原生的 Java SPI 不同的是，本库没有使用文件的方式获取目标实现类，而是通过 [Android Gradle Plugin Transform API](http://google.github.io/android-gradle-dsl/javadoc/current/com/android/build/api/transform/Transform.html) ，在代码编译阶段手动注册相关目标实现类。



由于实现的机制和 Java SPI 有区别，因此 Java SPI 中 [ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html) 类的功能并不完全相同，一些 API 也无法支持，仅仅实现了基本的 [iterator()](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html#iterator--) 方法。



## 使用方式

本库已经上传至 jcenter 仓库中，添加 jcenter 仓库便可以通过远程依赖的方式使用本库了。

![GitHub release](https://img.shields.io/github/release/yanglw/android-spi.svg?label=version)

```groovy
allprojects {
    repositories {
        jcenter()
    }
}
```



1. 对于提供 Service 的项目，需要依赖 android-spi-annotation ：

   ```groovy
   implementation 'me.yanglw:android-spi-annotation:(last-version)'
   ```

   所谓的 Service 其实就是一个声明功能的接口类，这个接口类的名称、方法没有任何要求。

   例如：

   ```java
   public interface SayService {
       void say(String text);
   }
   ```

   接口类只是功能的定义，还需要一个具体的实现类，这个类需要添加 `ServiceProvider` 注解。

   ```java
   import me.yanglw.android.spi.ServicePorvider;
   
   @ServieProvider(services={SayService.class})
   public class SayServiceImpl implements SayService {
       public void say(String text) {
           System.out.println(text);
       }
   }
   ```

   `ServiceProvider` 注解用于声明 Service 的实现类的，它有三个属性：

   1. services
      类型：Class[] ，用于标识 Service 列表。
   2. priorities
      类型：int[] ，用于标识 `services` 对应位置上的 Service 的优先级，数值越大优先级越高。数组的长度将跟随 `services` 的长度。若数组的长度超过 `services` 的长度，则丢弃多余的部分；若数组的长度少于 `services` 的长度，则补充长度，且默认值为 0 。
   3. singleton
      类型：boolean ，用于标识当前实现类是否需要实现单例。



2. 对于使用 Service 的项目，需要依赖 android-spi-loader ：

   ```groovy
   implementation 'me.yanglw:android-spi-loader:(last-version)'
   ```

   为应用项目添加 buildscript 依赖：

   ```groovy
   buildscript {
       repositories {
           jcenter()
       }
       dependencies {
           classpath 'me.yanglw:android-spi-compiler:(last-version)'
       }
   }
   ```

   引入 `me.yanglw.android.spi` 插件：

   ```groovy
   apply plugin: 'me.yanglw.android.spi'
   ```

   通过 ServiceLoader 获取目标 Service ：

   ```java
   ServiceLoader<SayService> loader = ServiceLoader.load(SayService.class);
   for(SayService service : loader) {
       service.say("hello");
   }
   ```



   **注意：**

   `me.yanglw.android.spi` 插件仅支持 `com.android.application` 不支持 `com.android.library` 。这说明，你无法在 Library 项目中使用这个插件。

## Demo

- [android-spi-demo](https://github.com/yanglw/android-spi-demo)

  一个借助本项目实现的 Android 模块化项目。



## License

    Copyright 2013 Liangwei Yang

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
