package java.util

object Collections {
    fun <T> unmodifiableList(list: List<T>): List<T> = list.toList()
}
