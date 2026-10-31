WBOX_NATIVE_PATH := $(call my-dir)
WBOX_HEV_PATH := $(WBOX_NATIVE_PATH)/hev-socks5-tunnel

include $(WBOX_HEV_PATH)/third-part/yaml/Android.mk
include $(WBOX_HEV_PATH)/third-part/lwip/Android.mk
ENABLE_MEMALLOC_SLICE := 0
include $(WBOX_HEV_PATH)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(WBOX_NATIVE_PATH)
SRCDIR := $(WBOX_HEV_PATH)/src
include $(WBOX_HEV_PATH)/build.mk
WBOX_HEV_SOURCE := $(filter-out $(SRCDIR)/hev-jni.c,$(SRCFILES))

include $(CLEAR_VARS)
LOCAL_MODULE := wboxtun
LOCAL_SRC_FILES := wbox-tun.c $(patsubst $(LOCAL_PATH)/%,%,$(WBOX_HEV_SOURCE))
LOCAL_C_INCLUDES := \
    $(WBOX_HEV_PATH)/src \
    $(WBOX_HEV_PATH)/src/misc \
    $(WBOX_HEV_PATH)/src/core/include \
    $(WBOX_HEV_PATH)/third-part/yaml/include \
    $(WBOX_HEV_PATH)/third-part/lwip/src/include \
    $(WBOX_HEV_PATH)/third-part/lwip/src/ports/include \
    $(WBOX_HEV_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS := -DENABLE_LIBRARY -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -fno-stack-clash-protection -fvisibility=hidden $(VERSION_CFLAGS)
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--exclude-libs,ALL
include $(BUILD_SHARED_LIBRARY)
