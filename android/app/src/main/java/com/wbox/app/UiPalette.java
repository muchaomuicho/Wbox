package com.wbox.app;

/** Semantic colors shared with sing-box-dashboard globals.css, without a WebView. */
final class UiPalette {
    final boolean dark;
    final int text, muted, background, card, surface, border, selected, good, goodSoft;
    UiPalette(boolean dark) {
        this.dark=dark;
        text=dark?0xffe5e5e5:0xff1a1a1a;
        muted=dark?0xffa1a1a1:0xff6e6e6e;
        background=dark?0xff1f1f1f:0xfffafafa;
        card=dark?0xff282828:0xffffffff;
        surface=dark?0xff242424:0xfff0f0f0;
        border=dark?0xff3b3b3b:0xffe8e8e8;
        selected=dark?0xff414141:0xffe3e3e3;
        good=dark?0xff88b49f:0xff3d8168;
        goodSoft=dark?0xff34403a:0xffe6efeb;
    }
}
