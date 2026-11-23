// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

data class ImageSpec(
    val id: String,
    val name: String,
    val abi: String,
    val minAppVersionCode: Int,
    val bytes: Long,
    val unpackedBytes: Long,
    val sha256: String,
    val sourceSha: String,
    val urls: List<String>
)
