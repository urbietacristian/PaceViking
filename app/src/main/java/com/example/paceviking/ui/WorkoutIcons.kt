package com.example.paceviking.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The two icons this app draws that `material-icons-core` does not carry.
 *
 * Declaring them here is what lets the app drop `material-icons-extended` — a
 * dependency of several thousand icons, kept for six. R8 already strips the
 * unused ones from the release build, so the shipped APK does not change; the
 * win is in build time and in the debug dex, which every incremental install
 * has to push to the device.
 *
 * Both are built with the same `materialIcon`/`materialPath` builders the
 * library's own generated icons use, from the same path data, so they render
 * identically to what they replace. They are extension properties on
 * `Icons.Filled` for the same reason: the call sites keep reading
 * `Icons.Default.ContentCopy`, and only the import changes.
 *
 * Path data from androidx.compose.material.icons.filled, Apache License 2.0.
 */

private var contentCopy: ImageVector? = null

val Icons.Filled.ContentCopy: ImageVector
    get() = contentCopy ?: materialIcon(name = "Filled.ContentCopy") {
        materialPath {
            moveTo(16.0f, 1.0f)
            lineTo(4.0f, 1.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineToRelative(2.0f)
            lineTo(4.0f, 3.0f)
            horizontalLineToRelative(12.0f)
            lineTo(16.0f, 1.0f)
            close()
            moveTo(19.0f, 5.0f)
            lineTo(8.0f, 5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(11.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 21.0f)
            lineTo(8.0f, 21.0f)
            lineTo(8.0f, 7.0f)
            horizontalLineToRelative(11.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }.also { contentCopy = it }

private var dragHandle: ImageVector? = null

val Icons.Filled.DragHandle: ImageVector
    get() = dragHandle ?: materialIcon(name = "Filled.DragHandle") {
        materialPath {
            moveTo(20.0f, 9.0f)
            horizontalLineTo(4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(16.0f)
            verticalLineTo(9.0f)
            close()
            moveTo(4.0f, 15.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(15.0f)
            close()
        }
    }.also { dragHandle = it }
