/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.rounded

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.Badge: ImageVector
    get() {
        if (_badge != null) {
            return _badge!!
        }
        _badge = materialIcon(name = "Rounded.Badge") {
            materialPath {
                moveTo(20.0f, 7.0f)
                horizontalLineToRelative(-5.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-2.0f)
                curveTo(9.9f, 2.0f, 9.0f, 2.9f, 9.0f, 4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 7.0f, 2.0f, 7.9f, 2.0f, 9.0f)
                verticalLineToRelative(11.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(9.0f)
                curveTo(22.0f, 7.9f, 21.1f, 7.0f, 20.0f, 7.0f)
                close()
                moveTo(9.0f, 12.0f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                curveToRelative(0.0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
                reflectiveCurveToRelative(-1.5f, -0.67f, -1.5f, -1.5f)
                curveTo(7.5f, 12.67f, 8.17f, 12.0f, 9.0f, 12.0f)
                close()
                moveTo(12.0f, 18.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(-0.43f)
                curveToRelative(0.0f, -0.6f, 0.36f, -1.15f, 0.92f, -1.39f)
                curveTo(7.56f, 15.9f, 8.26f, 15.75f, 9.0f, 15.75f)
                reflectiveCurveToRelative(1.44f, 0.15f, 2.08f, 0.43f)
                curveToRelative(0.55f, 0.24f, 0.92f, 0.78f, 0.92f, 1.39f)
                verticalLineTo(18.0f)
                close()
                moveTo(13.0f, 9.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(9.0f)
                close()
                moveTo(17.25f, 16.5f)
                horizontalLineToRelative(-2.5f)
                curveToRelative(-0.41f, 0.0f, -0.75f, -0.34f, -0.75f, -0.75f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.41f, 0.34f, -0.75f, 0.75f, -0.75f)
                horizontalLineToRelative(2.5f)
                curveToRelative(0.41f, 0.0f, 0.75f, 0.34f, 0.75f, 0.75f)
                verticalLineToRelative(0.0f)
                curveTo(18.0f, 16.16f, 17.66f, 16.5f, 17.25f, 16.5f)
                close()
                moveTo(17.25f, 13.5f)
                horizontalLineToRelative(-2.5f)
                curveToRelative(-0.41f, 0.0f, -0.75f, -0.34f, -0.75f, -0.75f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.41f, 0.34f, -0.75f, 0.75f, -0.75f)
                horizontalLineToRelative(2.5f)
                curveToRelative(0.41f, 0.0f, 0.75f, 0.34f, 0.75f, 0.75f)
                verticalLineToRelative(0.0f)
                curveTo(18.0f, 13.16f, 17.66f, 13.5f, 17.25f, 13.5f)
                close()
            }
        }
        return _badge!!
    }

private var _badge: ImageVector? = null
