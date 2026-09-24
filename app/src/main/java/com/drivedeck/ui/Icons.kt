package com.drivedeck.ui

import androidx.annotation.DrawableRes
import com.drivedeck.R
import com.drivedeck.data.MusicKind
import com.drivedeck.data.PlaceIcon

/** Shared icon mapping for the phone UI and the car screen (Material Design Icons, Apache 2.0). */
@DrawableRes
fun PlaceIcon.iconRes(): Int = when (this) {
    PlaceIcon.HOME -> R.drawable.ic_home
    PlaceIcon.WORK -> R.drawable.ic_work
    PlaceIcon.SCHOOL -> R.drawable.ic_school
    PlaceIcon.GYM -> R.drawable.ic_gym
    PlaceIcon.SPORT -> R.drawable.ic_sport
    PlaceIcon.CAFE -> R.drawable.ic_cafe
    PlaceIcon.SHOP -> R.drawable.ic_shop
    PlaceIcon.HEART -> R.drawable.ic_heart
    PlaceIcon.STAR -> R.drawable.ic_star
    PlaceIcon.PIN -> R.drawable.ic_pin
}

@DrawableRes
fun MusicKind.iconRes(): Int = when (this) {
    MusicKind.PLAYLIST -> R.drawable.ic_playlist
    MusicKind.ARTIST -> R.drawable.ic_artist
    MusicKind.SONG -> R.drawable.ic_song
    MusicKind.MIX -> R.drawable.ic_mix
}
