/* Test the real JNI bridge with a blocking HEV stub. Packet forwarding requires Android. */
#include <jni.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <io.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
static int fd_is_open(int fd, int ignored) { (void)ignored; return _get_osfhandle(fd)==-1 ? -1 : 0; }
#ifndef F_GETFD
#define F_GETFD 1
#endif
#define fcntl fd_is_open
#include "../wbox-tun.c"
#undef fcntl
static pthread_mutex_t stub_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t stub_condition=PTHREAD_COND_INITIALIZER;
static int stub_entered,stub_stopped,test_fd;
static atomic_int invocation_count,exception_count;
int hev_socks5_tunnel_main_from_file(const char *config,int fd) {
    assert(fd==test_fd && config && config[0]); atomic_fetch_add(&invocation_count,1);
    pthread_mutex_lock(&stub_mutex); stub_entered=1; pthread_cond_broadcast(&stub_condition);
    while(!stub_stopped) pthread_cond_wait(&stub_condition,&stub_mutex);
    pthread_mutex_unlock(&stub_mutex); return 0;
}
void hev_socks5_tunnel_quit(void) {
    pthread_mutex_lock(&stub_mutex); stub_stopped=1; pthread_cond_broadcast(&stub_condition); pthread_mutex_unlock(&stub_mutex);
}
void wbox_socks5_tunnel_reset_stop(void) {
    pthread_mutex_lock(&stub_mutex); stub_entered=stub_stopped=0; pthread_mutex_unlock(&stub_mutex);
}
static const char *get_string(JNIEnv *env,jstring str,jboolean *copy) { (void)env;(void)copy;return (const char *)str; }
static void release_string(JNIEnv *env,jstring str,const char *chars) { (void)env;(void)str;(void)chars; }
static jclass find_class(JNIEnv *env,const char *name) { (void)env;(void)name;return (jclass)1; }
static jint throw_new(JNIEnv *env,jclass type,const char *message) { (void)env;(void)type;(void)message;atomic_fetch_add(&exception_count,1);return 0; }
static const struct JNINativeInterface_ jni={.GetStringUTFChars=get_string,.ReleaseStringUTFChars=release_string,.FindClass=find_class,.ThrowNew=throw_new};
static JNIEnv environment=&jni;
static jint run_result;
static void *worker(void *unused) { (void)unused;run_result=Java_com_wbox_app_NativeTunnel_run(&environment,NULL,(jstring)"tunnel.yaml",test_fd);return NULL; }
int main(void) {
    test_fd=_open("NUL",_O_RDONLY);assert(test_fd>=0);
    Java_com_wbox_app_NativeTunnel_prepare(&environment,NULL);
    Java_com_wbox_app_NativeTunnel_stop(&environment,NULL);
    assert(Java_com_wbox_app_NativeTunnel_run(&environment,NULL,(jstring)"test",test_fd)==-ECANCELED);
    assert(atomic_load(&invocation_count)==0 && fd_is_open(test_fd,0)==0);
    Java_com_wbox_app_NativeTunnel_prepare(&environment,NULL);
    pthread_t thread;pthread_create(&thread,NULL,worker,NULL);
    pthread_mutex_lock(&stub_mutex);
    while(!stub_entered)pthread_cond_wait(&stub_condition,&stub_mutex);
    pthread_mutex_unlock(&stub_mutex);
    assert(Java_com_wbox_app_NativeTunnel_run(&environment,NULL,(jstring)"test",test_fd)==-EBUSY);
    Java_com_wbox_app_NativeTunnel_prepare(&environment,NULL);assert(atomic_load(&exception_count)==1);
    Java_com_wbox_app_NativeTunnel_stop(&environment,NULL);pthread_join(thread,NULL);assert(run_result==0);
    for(int i=0;i<1000;++i) {
        Java_com_wbox_app_NativeTunnel_prepare(&environment,NULL);pthread_create(&thread,NULL,worker,NULL);
        Java_com_wbox_app_NativeTunnel_stop(&environment,NULL);pthread_join(thread,NULL);
        assert((run_result==0 || run_result==-ECANCELED) && fd_is_open(test_fd,0)==0);
    }
    assert(Java_com_wbox_app_NativeTunnel_run(&environment,NULL,NULL,test_fd)==-EINVAL);
    assert(Java_com_wbox_app_NativeTunnel_run(&environment,NULL,(jstring)"test",-1)==-EINVAL);
    _close(test_fd);
    puts("PASS: early cancellation, running stop, busy rejection, 1000 scheduling races, borrowed fd ownership");return 0;
}
