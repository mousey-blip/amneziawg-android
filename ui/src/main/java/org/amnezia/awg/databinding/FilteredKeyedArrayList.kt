/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.databinding

import androidx.databinding.ObservableList

/**
 * Read-only keyed list mirroring [source], excluding elements that fail [predicate].
 * Structural changes to [source] are re-applied automatically; call [detach] once this
 * list is no longer bound, since [source] otherwise outlives it and keeps the callback alive.
 */
class FilteredKeyedArrayList<K, E : Keyed<out K>>(
    private val source: ObservableKeyedArrayList<K, E>,
    private val predicate: (E) -> Boolean
) : ObservableKeyedArrayList<K, E>() {
    private val sourceCallback = object : ObservableList.OnListChangedCallback<ObservableList<E>>() {
        override fun onChanged(sender: ObservableList<E>) = resync()
        override fun onItemRangeChanged(sender: ObservableList<E>, positionStart: Int, itemCount: Int) = resync()
        override fun onItemRangeInserted(sender: ObservableList<E>, positionStart: Int, itemCount: Int) = resync()
        override fun onItemRangeMoved(sender: ObservableList<E>, fromPosition: Int, toPosition: Int, itemCount: Int) = resync()
        override fun onItemRangeRemoved(sender: ObservableList<E>, positionStart: Int, itemCount: Int) = resync()
    }

    init {
        source.addOnListChangedCallback(sourceCallback)
        resync()
    }

    private fun resync() {
        val filtered = source.filter(predicate)
        clear()
        addAll(filtered)
    }

    fun detach() {
        source.removeOnListChangedCallback(sourceCallback)
    }
}
