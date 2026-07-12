package net.packset.rig

/** A USB vendor/product id pair, for augmenting the serial prober per rig. */
data class UsbId(val vendorId: Int, val productId: Int)
