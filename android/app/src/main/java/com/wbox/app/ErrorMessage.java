package com.wbox.app;

import java.net.*;
import javax.net.ssl.SSLException;

final class ErrorMessage {
    static String describe(Throwable error) {
        // Several Android network exceptions have no message. Never discard
        // their type or replace a useful outer message with a blank cause.
        Throwable cause=error;
        for(int i=0;i<10&&cause!=null;i++,cause=cause.getCause()) {
            if(cause instanceof UnknownHostException)return "无法解析订阅服务器地址，请检查网络和 DNS";
            if(cause instanceof SocketTimeoutException)return "下载配置超时，请检查网络或稍后重试";
            if(cause instanceof SSLException)return "订阅服务器 TLS 连接失败："+detail(cause);
            if(cause instanceof ConnectException)return "无法连接订阅服务器："+detail(cause);
        }
        for(cause=error;cause!=null;cause=cause.getCause()) {
            if(cause.getMessage()!=null&&!cause.getMessage().isBlank())return cause.getMessage();
            if(cause==cause.getCause())break;
        }
        return detail(error);
    }
    private static String detail(Throwable error) {String message=error.getMessage();return message==null||message.isBlank()?error.getClass().getSimpleName():message;}
}
