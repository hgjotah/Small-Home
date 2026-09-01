package com.ia.smallhome.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object SmallHomeIcons {
    val Visibility: ImageVector by lazy {
        ImageVector.Builder("Visibility", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4.5f)
                curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
                curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
                curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
                curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
                close()
                moveTo(12f, 17f)
                curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
                curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
                curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
                curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
                close()
                moveTo(12f, 9f)
                curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
                curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
                curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
                close()
            }
        }.build()
    }

    val VisibilityOff: ImageVector by lazy {
        ImageVector.Builder("VisibilityOff", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(2.27f, 1f)
                lineTo(1f, 2.27f)
                lineTo(4.55f, 5.82f)
                curveTo(2.76f, 7.15f, 1.39f, 9.27f, 1f, 12f)
                curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
                curveTo(13.57f, 19.5f, 15.06f, 19.19f, 16.42f, 18.64f)
                lineTo(21.73f, 23.95f)
                lineTo(23f, 22.68f)
                close()
                moveTo(12f, 17f)
                curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
                curveTo(7f, 11.31f, 7.14f, 10.65f, 7.39f, 10.05f)
                lineTo(9.02f, 11.68f)
                curveTo(9.01f, 11.79f, 9f, 11.89f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
                curveTo(12.11f, 15f, 12.21f, 14.99f, 12.32f, 14.98f)
                lineTo(13.95f, 16.61f)
                curveTo(13.35f, 16.86f, 12.69f, 17f, 12f, 17f)
                close()
                moveTo(12f, 4.5f)
                curveTo(10.89f, 4.5f, 9.82f, 4.65f, 8.81f, 4.92f)
                lineTo(10.54f, 6.65f)
                curveTo(11f, 6.55f, 11.49f, 6.5f, 12f, 6.5f)
                curveTo(15.79f, 6.5f, 19.17f, 8.63f, 20.82f, 12f)
                curveTo(20.23f, 13.2f, 19.42f, 14.27f, 18.44f, 15.14f)
                lineTo(19.86f, 16.56f)
                curveTo(21.34f, 15.31f, 22.45f, 13.75f, 23f, 12f)
                curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
                close()
            }
        }.build()
    }
}
