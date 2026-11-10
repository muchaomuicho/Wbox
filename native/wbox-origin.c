/* Bounded socket origin metadata. No Java callbacks on HEV coroutine stacks. */
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include "wbox-origin.h"
#define ORIGIN_LIMIT 1024
typedef struct { int used, fd, protocol, proxy_port, source_port, destination_port; char source[48], destination[48]; } Origin;
static Origin origins[ORIGIN_LIMIT];
static pthread_mutex_t origin_mutex = PTHREAD_MUTEX_INITIALIZER;
void wbox_origin_clear(void) { pthread_mutex_lock(&origin_mutex); memset(origins, 0, sizeof(origins)); pthread_mutex_unlock(&origin_mutex); }
void wbox_origin_remove(int fd) { pthread_mutex_lock(&origin_mutex); for(int i=0;i<ORIGIN_LIMIT;i++)if(origins[i].used&&origins[i].fd==fd)origins[i].used=0; pthread_mutex_unlock(&origin_mutex); }
void wbox_origin_add(int fd,int protocol,const char *source,int source_port,const char *destination,int destination_port) {
    struct sockaddr_storage address; socklen_t length=sizeof(address);
    if(fd<0||getsockname(fd,(struct sockaddr*)&address,&length))return;
    int port=address.ss_family==AF_INET?ntohs(((struct sockaddr_in*)&address)->sin_port):address.ss_family==AF_INET6?ntohs(((struct sockaddr_in6*)&address)->sin6_port):0;
    if(!port)return;
    pthread_mutex_lock(&origin_mutex);
    for(int i=0;i<ORIGIN_LIMIT;i++)if(!origins[i].used){
        Origin *o=&origins[i];o->used=1;o->fd=fd;o->protocol=protocol;o->proxy_port=port;o->source_port=source_port;o->destination_port=destination_port;
        snprintf(o->source,sizeof(o->source),"%s",source);snprintf(o->destination,sizeof(o->destination),"%s",destination);break;
    }
    pthread_mutex_unlock(&origin_mutex);
}
JNIEXPORT jobjectArray JNICALL Java_com_wbox_app_NativeTunnel_connectionOrigins(JNIEnv *env,jclass type) {
    (void)type;
    pthread_mutex_lock(&origin_mutex);
    int count=0;for(int i=0;i<ORIGIN_LIMIT;i++)if(origins[i].used)count++;
    jclass string_class=(*env)->FindClass(env,"java/lang/String");
    jobjectArray result=string_class?(*env)->NewObjectArray(env,count,string_class,NULL):NULL;
    if(result){int n=0;for(int i=0;i<ORIGIN_LIMIT;i++)if(origins[i].used){
        Origin *o=&origins[i];char line[160];snprintf(line,sizeof(line),"%d;%d;%s;%d;%s;%d",o->protocol,o->proxy_port,o->source,o->source_port,o->destination,o->destination_port);
        jstring value=(*env)->NewStringUTF(env,line);if(!value)break;(*env)->SetObjectArrayElement(env,result,n++,value);(*env)->DeleteLocalRef(env,value);
    }}
    pthread_mutex_unlock(&origin_mutex);return result;
}
