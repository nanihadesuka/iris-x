package com.nanihadesuka.irisx.feature.home

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The finger that started a selection also lifts over a cell: that lift must not count as a tap on it. */
class DragSelectGesture {
    var active = false
}

/**
 * Press and hold a photo, then slide: everything between it and the finger follows the first photo, selected or not,
 * like Google Photos. Sliding back undoes it, and near the top or bottom edge the grid scrolls.
 * [order] lists what can be selected, in the order shown.
 */
@Composable
fun <K : Any> Modifier.dragSelect(
    grid: LazyGridState,
    gesture: DragSelectGesture,
    order: List<K>,
    selected: List<K>,
    onChange: (List<K>) -> Unit,
): Modifier {
    val currentOrder by rememberUpdatedState(order)
    val currentSelected by rememberUpdatedState(selected)
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    return this.then(
        remember(grid, gesture) {
            Modifier.pointerInput(grid, gesture) {
                val edge = 64.dp.toPx()
                val speed = 14.dp.toPx()
                coroutineScope {
                    var anchor: K? = null
                    var baseline: List<K> = emptyList()
                    var adding = true
                    var finger = Offset.Zero
                    var scroller: Job? = null

                    @Suppress("UNCHECKED_CAST")
                    fun keyAt(point: Offset): K? {
                        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                            point.x.toInt() in it.offset.x until it.offset.x + it.size.width &&
                                point.y.toInt() in it.offset.y until it.offset.y + it.size.height
                        } ?: return null
                        return (item.key as? K)?.takeIf { it in currentOrder }
                    }

                    fun update() {
                        val from = anchor ?: return
                        val to = keyAt(finger) ?: return
                        val a = currentOrder.indexOf(from)
                        val b = currentOrder.indexOf(to)
                        if (a < 0 || b < 0) return
                        val range = currentOrder.subList(minOf(a, b), maxOf(a, b) + 1)
                        change(if (adding) (baseline + range).distinct() else baseline - range.toSet())
                    }

                    fun finish() {
                        anchor = null
                        scroller?.cancel()
                        gesture.active = false
                    }

                    detectDragGesturesAfterLongPress(
                        onDragStart = { point ->
                            val key = keyAt(point) ?: return@detectDragGesturesAfterLongPress
                            gesture.active = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            anchor = key
                            baseline = currentSelected
                            adding = key !in baseline
                            finger = point
                            update()
                            scroller = launch {
                                while (true) {
                                    val height = grid.layoutInfo.viewportSize.height
                                    val by = when {
                                        finger.y < edge -> -speed
                                        finger.y > height - edge -> speed
                                        else -> 0f
                                    }
                                    if (by != 0f) {
                                        grid.scrollBy(by)
                                        update()
                                    }
                                    delay(16)
                                }
                            }
                        },
                        onDrag = { event, _ ->
                            finger = event.position
                            update()
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    )
                }
            }
        },
    )
}
