package com.wbox.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Appearance preferences are independent of the selected light/dark mode. */
internal fun wboxColorScheme(dark:Boolean,accent:String="blue"):ColorScheme = when(accent) {
    "default"->if(dark) darkColorScheme(
        primary=Color(0xffe5e5e5),onPrimary=Color(0xff202020),primaryContainer=Color(0xff393939),onPrimaryContainer=Color(0xffeeeeee),
        secondary=Color(0xffc7c7c7),onSecondary=Color(0xff252525),secondaryContainer=Color(0xff393939),onSecondaryContainer=Color(0xffeeeeee),
        tertiary=Color(0xffc7c7c7),onTertiary=Color(0xff252525),tertiaryContainer=Color(0xff393939),onTertiaryContainer=Color(0xffeeeeee),
        background=Color(0xff1f1f1f),onBackground=Color(0xffe5e5e5),surface=Color(0xff1f1f1f),surfaceVariant=Color(0xff292929),
        onSurface=Color(0xffe5e5e5),onSurfaceVariant=Color(0xffb5b5b5),outline=Color(0xff929292),outlineVariant=Color(0xff3b3b3b),
        surfaceTint=Color(0xffe5e5e5),inverseSurface=Color(0xffe5e5e5),inverseOnSurface=Color(0xff303030),inversePrimary=Color(0xff555555)
    ) else lightColorScheme(
        primary=Color(0xff262626),onPrimary=Color.White,primaryContainer=Color(0xffe8e8e8),onPrimaryContainer=Color(0xff191919),
        secondary=Color(0xff575757),onSecondary=Color.White,secondaryContainer=Color(0xffe8e8e8),onSecondaryContainer=Color(0xff222222),
        tertiary=Color(0xff575757),onTertiary=Color.White,tertiaryContainer=Color(0xffe8e8e8),onTertiaryContainer=Color(0xff222222),
        background=Color(0xfffafafa),onBackground=Color(0xff1a1a1a),surface=Color(0xfffafafa),surfaceVariant=Color(0xfff0f0f0),
        onSurface=Color(0xff1a1a1a),onSurfaceVariant=Color(0xff646464),outline=Color(0xff888888),outlineVariant=Color(0xffe0e0e0),
        surfaceTint=Color(0xff262626),inverseSurface=Color(0xff303030),inverseOnSurface=Color(0xfff5f5f5),inversePrimary=Color(0xffc9c9c9)
    )
    "pink"->if(dark) darkColorScheme(
        primary=Color(0xffefb4d2),onPrimary=Color(0xff502138),primaryContainer=Color(0xff593247),onPrimaryContainer=Color(0xfffbd9e9),
        secondary=Color(0xffd2bac7),onSecondary=Color(0xff352431),secondaryContainer=Color(0xff423641),onSecondaryContainer=Color(0xffeddee6),
        tertiary=Color(0xffdcb4ce),onTertiary=Color(0xff432039),tertiaryContainer=Color(0xff4a3545),onTertiaryContainer=Color(0xfff5d8ec),
        background=Color(0xff1e181c),onBackground=Color(0xffefe4eb),surface=Color(0xff1e181c),surfaceVariant=Color(0xff2c242a),
        onSurface=Color(0xffefe4eb),onSurfaceVariant=Color(0xffc5b1bf),outline=Color(0xff9c8694),outlineVariant=Color(0xff4a3d46),
        surfaceTint=Color(0xffefb4d2),inverseSurface=Color(0xffefe4eb),inverseOnSurface=Color(0xff382b33),inversePrimary=Color(0xffa05278)
    ) else lightColorScheme(
        primary=Color(0xffa05278),onPrimary=Color.White,primaryContainer=Color(0xfff6dce8),onPrimaryContainer=Color(0xff57233e),
        secondary=Color(0xff846576),onSecondary=Color.White,secondaryContainer=Color(0xfff0e2e9),onSecondaryContainer=Color(0xff44303d),
        tertiary=Color(0xff8c637a),onTertiary=Color.White,tertiaryContainer=Color(0xfff3e4ec),onTertiaryContainer=Color(0xff67425a),
        background=Color(0xfff8f3f6),onBackground=Color(0xff2a2127),surface=Color(0xfff8f3f6),surfaceVariant=Color(0xfffffbfd),
        onSurface=Color(0xff2a2127),onSurfaceVariant=Color(0xff75636e),outline=Color(0xffa18b98),outlineVariant=Color(0xffe7dce3),
        surfaceTint=Color(0xffa05278),inverseSurface=Color(0xff382b33),inverseOnSurface=Color(0xfffaedf5),inversePrimary=Color(0xffefb4d2)
    )
    else->if(dark) darkColorScheme(
        primary=Color(0xffa4c3ff),onPrimary=Color(0xff12315e),primaryContainer=Color(0xff253f67),onPrimaryContainer=Color(0xffdbe7ff),
        secondary=Color(0xffb4c4e3),onSecondary=Color(0xff20324e),secondaryContainer=Color(0xff2d3b52),onSecondaryContainer=Color(0xffdce6f8),
        tertiary=Color(0xffdfb3cf),onTertiary=Color(0xff44263c),tertiaryContainer=Color(0xff463442),onTertiaryContainer=Color(0xffeac2dc),
        background=Color(0xff161920),onBackground=Color(0xffe5e8ef),surface=Color(0xff161920),surfaceVariant=Color(0xff232731),
        onSurface=Color(0xffe5e8ef),onSurfaceVariant=Color(0xffaab2c2),outline=Color(0xff7d889c),outlineVariant=Color(0xff373f4e),
        surfaceTint=Color(0xffa4c3ff),inverseSurface=Color(0xffe5e8ef),inverseOnSurface=Color(0xff2b303b),inversePrimary=Color(0xff3564bd)
    ) else lightColorScheme(
        primary=Color(0xff3564bd),onPrimary=Color.White,primaryContainer=Color(0xffdce7ff),onPrimaryContainer=Color(0xff193867),
        secondary=Color(0xff586b90),onSecondary=Color.White,secondaryContainer=Color(0xffe4eaf6),onSecondaryContainer=Color(0xff304261),
        tertiary=Color(0xff916780),onTertiary=Color.White,tertiaryContainer=Color(0xfff3e5ee),onTertiaryContainer=Color(0xff755169),
        background=Color(0xfff3f4f8),onBackground=Color(0xff20232b),surface=Color(0xfff3f4f8),surfaceVariant=Color(0xfffdfdff),
        onSurface=Color(0xff20232b),onSurfaceVariant=Color(0xff636a79),outline=Color(0xff8c94a5),outlineVariant=Color(0xffe0e4ed),
        surfaceTint=Color(0xff3564bd),inverseSurface=Color(0xff2b303b),inverseOnSurface=Color(0xffeef0f8),inversePrimary=Color(0xffa4c3ff)
    )
}
