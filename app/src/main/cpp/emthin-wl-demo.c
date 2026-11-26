/*
 * Minimal pure-POSIX Wayland SHM + xdg_shell client for Termux -> emthin testing.
 * Released under GPL-3.0 alongside emthin + termux-app.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static uint32_t g_compositor_id = 0, g_compositor_ver = 4;
static uint32_t g_shm_id = 0, g_shm_ver = 1;
static uint32_t g_xdg_base_id = 0, g_xdg_base_ver = 2;
static int g_configured_w = 360, g_configured_h = 240;
static int g_pending_w = 360, g_pending_h = 240;
static int g_has_configure = 0;
static int g_sync_done = 0;
static int g_frame_done = 0;
static int g_should_close = 0;
static uint32_t g_ack_serial = 0;

static void write_u32(uint8_t *dst, uint32_t v) {
    memcpy(dst, &v, 4);
}

static uint32_t read_u32(const uint8_t *src) {
    uint32_t v = 0;
    memcpy(&v, src, 4);
    return v;
}

static size_t append_str(uint8_t *dst, const char *s) {
    uint32_t len = (uint32_t)strlen(s) + 1;
    uint32_t padded = (len + 3U) & ~3U;
    write_u32(dst, len);
    memset(dst + 4, 0, padded);
    memcpy(dst + 4, s, len);
    return 4 + padded;
}

static int send_msg(int fd, uint32_t obj, uint16_t op, const uint8_t *payload, size_t plen, int pass_fd) {
    size_t sz = 8 + plen;
    uint8_t stack_buf[512];
    uint8_t *buf = sz <= sizeof(stack_buf) ? stack_buf : (uint8_t *)malloc(sz);
    if (!buf) return -1;
    write_u32(buf, obj);
    write_u32(buf + 4, ((uint32_t)sz << 16) | (uint32_t)op);
    if (plen > 0) memcpy(buf + 8, payload, plen);

    struct iovec iov = { .iov_base = buf, .iov_len = sz };
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    uint8_t cbuf[CMSG_SPACE(sizeof(int))];
    if (pass_fd >= 0) {
        memset(cbuf, 0, sizeof(cbuf));
        msg.msg_control = cbuf;
        msg.msg_controllen = sizeof(cbuf);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &pass_fd, sizeof(int));
    }

    ssize_t rc = sendmsg(fd, &msg, 0);
    if (buf != stack_buf) free(buf);
    return rc < 0 ? -1 : 0;
}

static int create_shm_fd(size_t size, const char *runtime_dir) {
    int fd = -1;
#ifdef __NR_memfd_create
    fd = (int)syscall(__NR_memfd_create, "emthin-wl-demo", MFD_CLOEXEC);
#endif
    if (fd < 0 && runtime_dir && runtime_dir[0]) {
        char tmpl[512];
        snprintf(tmpl, sizeof(tmpl), "%s/emthin-wl-shm-XXXXXX", runtime_dir);
        fd = mkstemp(tmpl);
        if (fd >= 0) unlink(tmpl);
    }
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)size) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int drain_events(int sock, uint8_t *rbuf, size_t *rlen, size_t rcap, int timeout_ms) {
    struct pollfd pfd = { .fd = sock, .events = POLLIN, .revents = 0 };
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr < 0) return errno == EINTR ? 0 : -1;
    if (pr == 0) return 0;

    if (pfd.revents & POLLIN) {
        ssize_t n = recv(sock, rbuf + *rlen, rcap - *rlen, 0);
        if (n <= 0) return -1;
        *rlen += (size_t)n;
    }

    size_t off = 0;
    while (*rlen - off >= 8) {
        uint32_t obj = read_u32(rbuf + off);
        uint32_t w1 = read_u32(rbuf + off + 4);
        uint32_t sz = w1 >> 16;
        uint16_t op = (uint16_t)(w1 & 0xffffU);
        if (sz < 8 || *rlen - off < sz) break;
        const uint8_t *pl = rbuf + off + 8;
        size_t plen = sz - 8;

        if (obj == 2 && op == 0 && plen >= 12) {
            uint32_t name = read_u32(pl);
            uint32_t slen = read_u32(pl + 4);
            uint32_t spadded = (slen + 3U) & ~3U;
            if (8 + spadded + 4 <= plen) {
                const char *iface = (const char *)(pl + 8);
                uint32_t ver = read_u32(pl + 8 + spadded);
                if (strcmp(iface, "wl_compositor") == 0) { g_compositor_id = name; g_compositor_ver = ver; }
                else if (strcmp(iface, "wl_shm") == 0) { g_shm_id = name; g_shm_ver = ver; }
                else if (strcmp(iface, "xdg_wm_base") == 0) { g_xdg_base_id = name; g_xdg_base_ver = ver; }
            }
        } else if (obj == 3 && op == 0) {
            g_sync_done = 1;
        } else if (obj == 6 && op == 0 && plen >= 4) {
            uint8_t pong[4];
            write_u32(pong, read_u32(pl));
            send_msg(sock, 6, 3, pong, 4, -1); // xdg_wm_base.pong
        } else if (obj == 9 && op == 0 && plen >= 8) {
            int32_t w = (int32_t)read_u32(pl);
            int32_t h = (int32_t)read_u32(pl + 4);
            if (w > 0) g_pending_w = w;
            if (h > 0) g_pending_h = h;
        } else if (obj == 9 && op == 1) {
            g_should_close = 1;
        } else if (obj == 8 && op == 0 && plen >= 4) {
            g_ack_serial = read_u32(pl);
            g_configured_w = g_pending_w;
            g_configured_h = g_pending_h;
            g_has_configure = 1;
        } else if (obj >= 13 && op == 0) {
            g_frame_done = 1;
        }
        off += sz;
    }
    if (off > 0) {
        memmove(rbuf, rbuf + off, *rlen - off);
        *rlen -= off;
    }
    return 0;
}

static void bind_global(int sock, uint32_t name, const char *iface, uint32_t ver, uint32_t new_id) {
    uint8_t pl[128];
    size_t pos = 0;
    write_u32(pl + pos, name); pos += 4;
    pos += append_str(pl + pos, iface);
    write_u32(pl + pos, ver); pos += 4;
    write_u32(pl + pos, new_id); pos += 4;
    send_msg(sock, 2, 0, pl, pos, -1);
}

int main(int argc, char **argv) {
    const char *app_id = "emthin-wl-demo";
    const char *title = "Wayland Demo";
    int seconds = 8;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--app-id") == 0 && i + 1 < argc) app_id = argv[++i];
        else if (strcmp(argv[i], "--title") == 0 && i + 1 < argc) title = argv[++i];
        else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc) seconds = atoi(argv[++i]);
    }
    if (seconds < 1) seconds = 1;

    const char *sock_override = getenv("EMTHIN_WAYLAND_SOCKET");
    const char *xdg_dir = getenv("XDG_RUNTIME_DIR");
    const char *wl_disp = getenv("WAYLAND_DISPLAY");
    char sock_path[512];
    if (sock_override && sock_override[0]) {
        snprintf(sock_path, sizeof(sock_path), "%s", sock_override);
    } else if (xdg_dir && xdg_dir[0]) {
        snprintf(sock_path, sizeof(sock_path), "%s/%s", xdg_dir, (wl_disp && wl_disp[0]) ? wl_disp : "wayland-0");
    } else {
        fprintf(stderr, "emthin-wl-demo: XDG_RUNTIME_DIR/EMTHIN_WAYLAND_SOCKET not set\n");
        return 2;
    }

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        perror("socket");
        return 1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(sock_path) >= sizeof(addr.sun_path)) {
        fprintf(stderr, "socket path too long: %s\n", sock_path);
        close(sock);
        return 1;
    }
    strcpy(addr.sun_path, sock_path);
    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        perror("connect wayland socket");
        close(sock);
        return 1;
    }

    uint8_t reg_pl[4], sync_pl[4];
    write_u32(reg_pl, 2);
    write_u32(sync_pl, 3);
    send_msg(sock, 1, 1, reg_pl, 4, -1); // get_registry -> 2
    send_msg(sock, 1, 0, sync_pl, 4, -1); // sync -> 3

    uint8_t rbuf[65536];
    size_t rlen = 0;
    while (!g_sync_done) {
        if (drain_events(sock, rbuf, &rlen, sizeof(rbuf), 500) < 0) return 1;
    }
    if (!g_compositor_id || !g_shm_id || !g_xdg_base_id) {
        fprintf(stderr, "missing required Wayland globals\n");
        return 1;
    }

    bind_global(sock, g_compositor_id, "wl_compositor", g_compositor_ver < 4 ? g_compositor_ver : 4, 4);
    bind_global(sock, g_shm_id, "wl_shm", 1, 5);
    bind_global(sock, g_xdg_base_id, "xdg_wm_base", g_xdg_base_ver < 2 ? g_xdg_base_ver : 2, 6);

    uint8_t pl[256];
    // wl_compositor.create_surface -> 7
    write_u32(pl, 7);
    send_msg(sock, 4, 0, pl, 4, -1);
    // xdg_wm_base.get_xdg_surface -> 8, surface 7
    write_u32(pl, 8); write_u32(pl + 4, 7);
    send_msg(sock, 6, 2, pl, 8, -1);
    // xdg_surface.get_toplevel -> 9
    write_u32(pl, 9);
    send_msg(sock, 8, 1, pl, 4, -1);
    // set_title & set_app_id & commit
    size_t tlen = append_str(pl, title);
    send_msg(sock, 9, 2, pl, tlen, -1);
    size_t alen = append_str(pl, app_id);
    send_msg(sock, 9, 3, pl, alen, -1);
    send_msg(sock, 7, 6, NULL, 0, -1);

    for (int i = 0; i < 100 && !g_has_configure; i++) {
        if (drain_events(sock, rbuf, &rlen, sizeof(rbuf), 50) < 0) break;
    }

    uint32_t next_id = 10;
    int first_commit = 1;
    time_t start_t = time(NULL);
    while (!g_should_close && (time(NULL) - start_t) < seconds) {
        if (g_has_configure || first_commit) {
            g_has_configure = 0;
            if (g_ack_serial != 0) {
                write_u32(pl, g_ack_serial);
                send_msg(sock, 8, 4, pl, 4, -1); // ack_configure
            }
            int w = g_configured_w > 0 ? g_configured_w : 360;
            int h = g_configured_h > 0 ? g_configured_h : 240;
            int stride = w * 4;
            size_t bsize = (size_t)stride * (size_t)h;
            int shm_fd = create_shm_fd(bsize, xdg_dir);
            if (shm_fd < 0) {
                fprintf(stderr, "failed creating shm fd\n");
                return 1;
            }
            uint8_t *px = (uint8_t *)mmap(NULL, bsize, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
            if (px == MAP_FAILED) { close(shm_fd); return 1; }
            for (int y = 0; y < h; y++) {
                int by = y < 4 || y >= h - 4;
                for (int x = 0; x < w; x++) {
                    int bx = by || x < 4 || x >= w - 4;
                    uint8_t *p = px + ((size_t)y * (size_t)w + (size_t)x) * 4;
                    if (bx) {
                        p[0] = 0x55; p[1] = 0xc2; p[2] = 0xf0; p[3] = 0xff; // BGRA cyan border
                    } else {
                        p[0] = (uint8_t)(40 + ((x * 160) / (w > 1 ? w : 1)));
                        p[1] = (uint8_t)(80 + ((y * 120) / (h > 1 ? h : 1)));
                        p[2] = 0x24;
                        p[3] = 0xff;
                    }
                }
            }
            munmap(px, bsize);

            uint32_t pool_id = next_id++;
            uint32_t buf_id = next_id++;
            uint32_t cb_id = next_id++;
            write_u32(pl, pool_id);
            write_u32(pl + 4, (uint32_t)bsize);
            send_msg(sock, 5, 0, pl, 8, shm_fd);
            close(shm_fd);

            write_u32(pl, buf_id);
            write_u32(pl + 4, 0);
            write_u32(pl + 8, (uint32_t)w);
            write_u32(pl + 12, (uint32_t)h);
            write_u32(pl + 16, (uint32_t)stride);
            write_u32(pl + 20, 0); // WL_SHM_FORMAT_ARGB8888
            send_msg(sock, pool_id, 0, pl, 24, -1); // wl_shm_pool.create_buffer

            // wl_surface.attach(buf_id, 0, 0)
            write_u32(pl, buf_id);
            write_u32(pl + 4, 0);
            write_u32(pl + 8, 0);
            send_msg(sock, 7, 1, pl, 12, -1);

            // wl_surface.frame(cb_id)
            write_u32(pl, cb_id);
            send_msg(sock, 7, 3, pl, 4, -1);

            // wl_surface.commit()
            send_msg(sock, 7, 6, NULL, 0, -1);

            // wl_shm_pool.destroy()
            send_msg(sock, pool_id, 1, NULL, 0, -1);

            if (first_commit) {
                first_commit = 0;
                printf("emthin-wl-demo: committed %dx%d appId=%s\n", w, h, app_id);
                fflush(stdout);
            }
        }
        if (drain_events(sock, rbuf, &rlen, sizeof(rbuf), 100) < 0) break;
    }
    close(sock);
    return 0;
}
