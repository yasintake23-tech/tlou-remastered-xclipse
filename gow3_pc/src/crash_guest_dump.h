/* Bounded Linux/Bionic guest diagnostics for fatal signal context. */
#ifndef TLOU_CRASH_GUEST_DUMP_H
#define TLOU_CRASH_GUEST_DUMP_H
#define _GNU_SOURCE
#include "guest_cpu.h"
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>
#include <pthread.h>
#include <ucontext.h>
#include <errno.h>

__attribute__((weak)) void **runtime_thread_fiber_slot(void);

static inline void crash_hex(char *out, uint64_t value) {
    static const char digits[] = "0123456789abcdef";
    for (unsigned i=0;i<16;++i) out[i]=digits[(value >> (60-i*4)) & 15];
}

static inline void dump_guest_fault_regs(int fd, const GuestRegs *regs, uintptr_t image_base, const void *ucontext) {
    char tname[32] = {0};
    if (pthread_getname_np(pthread_self(), tname, sizeof(tname)) == 0 && tname[0]) {
        char tline[96];
        int len = snprintf(tline, sizeof(tline), "Guest thread: '%s' (tid=%d)\n", tname, (int)gettid());
        if (len > 0) { ssize_t r = write(fd, tline, (size_t)len); (void)r; }
    }
    if (runtime_thread_fiber_slot) {
        void **slot = runtime_thread_fiber_slot();
        if (slot && *slot) {
            char fline[80];
            int len = snprintf(fline, sizeof(fline), "Active fiber context: %p\n", *slot);
            if (len > 0) { ssize_t r = write(fd, fline, (size_t)len); (void)r; }
        }
    }
    static const char names[GUEST_GPRS][4]={"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi",
                                           "r8 ","r9 ","r10","r11","r12","r13","r14","r15"};
    for (unsigned i=0;i<GUEST_GPRS;++i) {
        char line[]="Guest register rax=0000000000000000\n";
        memcpy(line+15,names[i],3);crash_hex(line+19,regs->gpr[i]);
        ssize_t result=write(fd,line,sizeof(line)-1);(void)result;
    }
    if (regs->has_xmm) {
        for (int i = 0; i < 16; ++i) {
            char xline[96];
            int len = snprintf(xline, sizeof(xline), "Guest register xmm%d=%016llx%016llx (GUEST XMM)\n",
                               i, (unsigned long long)regs->xmm[i][1], (unsigned long long)regs->xmm[i][0]);
            if (len > 0) { ssize_t r = write(fd, xline, (size_t)len); (void)r; }
        }
    }
    if (ucontext) {
#if defined(__aarch64__)
        const ucontext_t *uc = (const ucontext_t *)ucontext;
        const unsigned char *at = (const unsigned char *)uc->uc_mcontext.__reserved;
        const unsigned char *end = at + sizeof(uc->uc_mcontext.__reserved);
        while (at + 8 <= end) {
            uint32_t magic = 0, size = 0;
            memcpy(&magic, at, 4);
            memcpy(&size, at + 4, 4);
            if (magic == 0x46508001 && size >= 16 + 32 * 16) {
                const uint64_t *v = (const uint64_t *)(at + 16);
                for (int i = 0; i < 16; ++i) {
                    char xline[96];
                    int len = snprintf(xline, sizeof(xline), "Host SIMD register v%d=%016llx%016llx (HOST ARM64 SIMD)\n",
                                       i, (unsigned long long)v[i*2+1], (unsigned long long)v[i*2]);
                    if (len > 0) { ssize_t r = write(fd, xline, (size_t)len); (void)r; }
                }
                break;
            }
            if (!magic || size < 8) break;
            at += size;
        }
#elif defined(__x86_64__)
        if (!regs->has_xmm) {
            const ucontext_t *uc = (const ucontext_t *)ucontext;
            if (uc->uc_mcontext.fpregs) {
                const uint32_t *x = (const uint32_t *)uc->uc_mcontext.fpregs->_xmm;
                for (int i = 0; i < 16; ++i) {
                    char xline[96];
                    int len = snprintf(xline, sizeof(xline), "Guest register xmm%d=%08x%08x%08x%08x (GUEST XMM)\n",
                                       i, x[i*4+3], x[i*4+2], x[i*4+1], x[i*4]);
                    if (len > 0) { ssize_t r = write(fd, xline, (size_t)len); (void)r; }
                }
            }
        }
#endif
    }
    uint8_t opcodes[64];
    memset(opcodes, 0, sizeof(opcodes));
    uintptr_t start_rip = regs->rip >= 32 ? regs->rip - 32 : regs->rip;
    size_t count = regs->rip >= 32 ? 64 : (regs->rip ? 32 : 0);
    struct iovec l_op = {opcodes, count};
    struct iovec r_op = {(void *)start_rip, count};
    errno = 0;
    ssize_t nread = count ? process_vm_readv(getpid(), &l_op, 1, &r_op, 1, 0) : 0;
    int err = (nread < 0) ? errno : 0;

    char diag[180];
    int dlen = snprintf(diag, sizeof(diag),
                        "Guest opcodes around RIP [0x%016llx..0x%016llx]: requested=%zu read=%zd errno=%d%s\n",
                        (unsigned long long)start_rip,
                        (unsigned long long)(start_rip + (count ? count - 1 : 0)),
                        count, nread, err,
                        err == EFAULT ? " (EFAULT)" :
                        (err == EPERM ? " (EPERM)" :
                        (err == ENOSYS ? " (ENOSYS)" : "")));
    if (dlen > 0) { ssize_t r = write(fd, diag, (size_t)dlen); (void)r; }

    if (nread > 0) {
        char oline[384];
        int pos = snprintf(oline, sizeof(oline), "  Hex: ");
        static const char hexchars[] = "0123456789abcdef";
        size_t valid_bytes = (size_t)nread > count ? count : (size_t)nread;
        for (unsigned i = 0; i < valid_bytes; ++i) {
            if (start_rip + i == regs->rip) {
                if (pos < (int)sizeof(oline) - 4) oline[pos++] = '[';
            }
            if (pos < (int)sizeof(oline) - 4) {
                oline[pos++] = hexchars[(opcodes[i] >> 4) & 0xf];
                oline[pos++] = hexchars[opcodes[i] & 0xf];
            }
            if (start_rip + i == regs->rip) {
                if (pos < (int)sizeof(oline) - 4) oline[pos++] = ']';
            } else {
                if (pos < (int)sizeof(oline) - 4) oline[pos++] = ' ';
            }
            if (i == 31) {
                if (pos < (int)sizeof(oline) - 8) {
                    oline[pos++] = '\n';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                }
            }
        }
        if (pos < (int)sizeof(oline) - 2) oline[pos++] = '\n';
        ssize_t result = write(fd, oline, (size_t)pos); (void)result;
    } else {
        const char unread_msg[] = "  Hex: <memory unmapped or read error - no synthetic bytes generated>\n";
        ssize_t r = write(fd, unread_msg, sizeof(unread_msg) - 1); (void)r;
    }
    uintptr_t rbp=(uintptr_t)regs->gpr[GUEST_RBP];
    for (unsigned depth=0;depth<16 && rbp;++depth) {
        uintptr_t frame[2];
        struct iovec local={frame,sizeof(frame)},remote={(void *)rbp,sizeof(frame)};
        if (process_vm_readv(getpid(),&local,1,&remote,1,0)!=(ssize_t)sizeof(frame)) break;
        char line[]="Guest caller=0000000000000000 image-relative=0000000000000000\n";
        crash_hex(line+13,frame[1]);crash_hex(line+45,frame[1]-image_base);
        ssize_t result=write(fd,line,sizeof(line)-1);(void)result;
        if (frame[0]<=rbp || frame[0]-rbp>1024*1024) break;
        rbp=frame[0];
    }
}
#endif
