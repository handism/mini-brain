package com.minibrain.ui.components

import android.net.Uri

/**
 * SAF の tree URI を画面表示用のフォルダパスにする。
 * lastPathSegment は `primary:Documents/notes` や `1234-ABCD:notes` のように
 * ボリューム ID が前置されるので、`:` より前を落とす。
 */
fun folderDisplayName(treeUri: String): String {
    val segment = Uri.parse(treeUri).lastPathSegment ?: return treeUri
    return segment.substringAfter(':').ifEmpty { segment }
}
