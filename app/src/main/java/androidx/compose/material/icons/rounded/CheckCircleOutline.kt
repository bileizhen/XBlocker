// Vendored from AndroidX Material Icons 1.7.8; original Apache-2.0 notice below.
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

public val Icons.Rounded.CheckCircleOutline: ImageVector
    get() {
        if (_checkCircleOutline != null) {
            return _checkCircleOutline!!
        }
        _checkCircleOutline = materialIcon(name = "Rounded.CheckCircleOutline") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
                close()
                moveTo(15.88f, 8.29f)
                lineTo(10.0f, 14.17f)
                lineToRelative(-1.88f, -1.88f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(2.59f, 2.59f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineTo(17.3f, 9.7f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.42f, 0.0f)
                close()
            }
        }
        return _checkCircleOutline!!
    }

private var _checkCircleOutline: ImageVector? = null
