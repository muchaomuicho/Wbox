package com.wbox.app;

import java.io.*;
import java.net.*;

final class SubscriptionFetch {
    static final String USER_AGENT="clash.meta/1.19.33";
    static String fetch(String address)throws Exception {
        URL url=new URL(address.trim());
        if(!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))throw new IllegalArgumentException("订阅必须是 HTTP / HTTPS 地址");
        for(int attempt=0;;attempt++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            try { return once(url,attempt); }
            catch(Retryable e){if(attempt>0)throw new IOException(e.getMessage());}
            catch(SocketTimeoutException e){if(attempt>0)throw new IOException("订阅下载超时，已重试一次。请检查当前网络、代理与订阅服务。");}
            catch(ConnectException|UnknownHostException e){if(attempt>0)throw new IOException("无法连接订阅服务，已重试一次。请检查 DNS 与当前网络。");}
            Thread.sleep(750);
        }
    }
    private static String once(URL initial,int attempt)throws Exception {
        URL url=initial;long deadline=System.nanoTime()+30_000_000_000L;
        for(int redirect=0;redirect<6;redirect++) {
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);c.setReadTimeout(remaining(deadline));c.setRequestProperty("User-Agent",USER_AGENT);
            try {
                int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=c.getHeaderField("Location");if(location==null)throw new IOException("订阅服务器重定向缺少地址。");
                    URL next=new URL(url,location);
                    if(!next.getProtocol().equals("http")&&!next.getProtocol().equals("https") || url.getProtocol().equals("https")&&!next.getProtocol().equals("https"))throw new IOException("订阅重定向使用了不安全的协议。");
                    url=next;continue;
                }
                if(status==502||status==503||status==504)throw new Retryable("订阅请求返回 HTTP "+status+"，重试后仍失败。可能是订阅服务或当前代理的上游故障，请稍后重试或更换网络。");
                if(status!=200)throw new IOException("订阅服务器返回 HTTP "+status+"。请检查订阅地址、有效期或访问限制。");
                if(c.getContentLengthLong()>4*1024*1024)throw new IOException("配置超过 4 MiB。");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int n;
                    while(true){if(Thread.currentThread().isInterrupted())throw new InterruptedException();c.setReadTimeout(remaining(deadline));n=in.read(buffer);if(n<0)break;if(out.size()+n>4*1024*1024)throw new IOException("配置超过 4 MiB。");out.write(buffer,0,n);}
                    return out.toString("UTF-8");
                }
            }finally{c.disconnect();}
        }
        throw new IOException("订阅重定向次数过多。");
    }
    private static int remaining(long deadline)throws SocketTimeoutException {long ms=(deadline-System.nanoTime())/1_000_000;if(ms<=0)throw new SocketTimeoutException();return (int)Math.min(30000,ms);}
    private static final class Retryable extends IOException {Retryable(String message){super(message);}}
}
