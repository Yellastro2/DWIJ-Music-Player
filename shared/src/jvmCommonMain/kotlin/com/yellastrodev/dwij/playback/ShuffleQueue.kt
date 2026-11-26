package com.yellastrodev.dwij.playback

import kotlin.random.Random

/**
 * Вставляет новые индексы в случайные позиции после [lastPlayedPosition].
 * Старые индексы сохраняют взаимный порядок, включая историю.
 */
fun interleaveShuffleTracks(
    order: List<Int>,
    lastPlayedPosition: Int,
    addedIndices: IntRange,
    random: Random = Random.Default,
): List<Int> {
    val added = addedIndices.shuffled(random)
    var oldIndex = (lastPlayedPosition + 1).coerceIn(0, order.size)
    var newIndex = 0
    return buildList(order.size + added.size) {
        addAll(order.subList(0, oldIndex))
        while (newIndex < added.size && oldIndex < order.size) {
            val remainingNew = added.size - newIndex
            val remainingOld = order.size - oldIndex
            if (random.nextInt(remainingNew + remainingOld) < remainingNew) {
                add(added[newIndex++])
            } else {
                add(order[oldIndex++])
            }
        }
        addAll(order.subList(oldIndex, order.size))
        addAll(added.subList(newIndex, added.size))
    }
}
