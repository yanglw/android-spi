package me.yanglw.android.spi

/**
 * service provider 的某一项 service 的信息载体。
 *
 * Created by yanglw on 2018/4/19.
 */
class ProviderInfo(
    /** service provider 的类名。 */
    val name: String,
    /** service provider 的优先级。 */
    val priority: Int) : Comparable<ProviderInfo> {

  override fun compareTo(other: ProviderInfo): Int {
    return other.priority - this.priority
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ProviderInfo

    if (name != other.name) return false
    if (priority != other.priority) return false

    return true
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + priority
    return result
  }
}
