#ifndef WBOX_ORIGIN_H
#define WBOX_ORIGIN_H
void wbox_origin_add(int fd, int protocol, const char *source, int source_port,
                     const char *destination, int destination_port);
void wbox_origin_remove(int fd);
void wbox_origin_clear(void);
#endif
