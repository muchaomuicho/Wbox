package com.wbox.app;

final class PollPolicy {
    static long delaySeconds(boolean visible,boolean livePage) {return !visible?-1:livePage?1:5;}
}
