package com.zenes.vpn.bg

import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.NetworkInterfaceIterator

class LibStringIterator(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun next(): String = items[index++]
    override fun len(): Int = items.size
}

class LibNetworkInterfaceIterator(private val items: List<LibboxNetworkInterface>) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun next(): LibboxNetworkInterface = items[index++]
}
