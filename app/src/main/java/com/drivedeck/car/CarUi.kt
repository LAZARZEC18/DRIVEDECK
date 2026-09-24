package com.drivedeck.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/** Small helpers shared by the car screens. */
internal object CarUi {
    /** DRIVEDECK accent (electric green) with a slightly deeper variant for light car themes. */
    val ACCENT: CarColor = CarColor.createCustom(0xFF00C853.toInt(), 0xFF00E676.toInt())

    fun icon(ctx: CarContext, @DrawableRes res: Int, tint: CarColor? = CarColor.DEFAULT): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, res)).apply { tint?.let { setTint(it) } }.build()

    /** How many grid items this car allows (varies by car and whether you're moving). */
    fun gridLimit(ctx: CarContext, fallback: Int = 6): Int = try {
        if (ctx.carAppApiLevel >= 2) {
            ctx.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } else fallback
    } catch (_: Exception) {
        fallback
    }
}
