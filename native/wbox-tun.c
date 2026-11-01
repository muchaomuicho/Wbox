/* Wbox JNI bridge. See README.md for descriptor ownership. */
#include <jni.h>
#include <pthread.h>
#include <errno.h>
#include <fcntl.h>
#include "hev-main.h"
#include "wbox-origin.h"

static pthread_mutex_t state_mutex = PTHREAD_MUTEX_INITIALIZER;
static int running;
static int cancelled;
/* Wbox's small HEV patch clears an unconsumed early-stop token on teardown. */
void wbox_socks5_tunnel_reset_stop(void);

JNIEXPORT void JNICALL
Java_com_wbox_app_NativeTunnel_prepare(JNIEnv *env, jclass type)
{
    (void)type;
    pthread_mutex_lock(&state_mutex);
    if (running) {
        pthread_mutex_unlock(&state_mutex);
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (exception)
            (*env)->ThrowNew(env, exception, "Previous VPN tunnel is still running");
        return;
    }
    cancelled = 0;
    wbox_origin_clear();
    pthread_mutex_unlock(&state_mutex);
}

JNIEXPORT jint JNICALL
Java_com_wbox_app_NativeTunnel_run(JNIEnv *env, jclass type, jstring config, jint fd)
{
    (void)type;
    if (!config || fd < 0 || fcntl(fd, F_GETFD) < 0)
        return -EINVAL;
    const char *path = (*env)->GetStringUTFChars(env, config, NULL);
    if (!path)
        return -ENOMEM;

    pthread_mutex_lock(&state_mutex);
    if (cancelled) {
        pthread_mutex_unlock(&state_mutex);
        (*env)->ReleaseStringUTFChars(env, config, path);
        return -ECANCELED;
    }
    if (running) {
        pthread_mutex_unlock(&state_mutex);
        (*env)->ReleaseStringUTFChars(env, config, path);
        return -EBUSY;
    }
    running = 1;
    pthread_mutex_unlock(&state_mutex);

    int result = hev_socks5_tunnel_main_from_file(path, fd);

    pthread_mutex_lock(&state_mutex);
    wbox_socks5_tunnel_reset_stop();
    running = 0;
    pthread_mutex_unlock(&state_mutex);
    (*env)->ReleaseStringUTFChars(env, config, path);
    /* fd is borrowed. The Java owner closes it after this call returns. */
    return result;
}

JNIEXPORT void JNICALL
Java_com_wbox_app_NativeTunnel_stop(JNIEnv *env, jclass type)
{
    (void)env;
    (void)type;
    pthread_mutex_lock(&state_mutex);
    cancelled = 1;
    if (running)
        hev_socks5_tunnel_quit();
    pthread_mutex_unlock(&state_mutex);
}
