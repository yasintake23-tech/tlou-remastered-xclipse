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
                    char xline[80];
                    int len = snprintf(xline, sizeof(xline), "Guest register xmm%d=%016llx%016llx\n",
                                       i, (unsigned long long)v[i*2+1], (unsigned long long)v[i*2]);
                    if (len > 0) { ssize_t r = write(fd, xline, (size_t)len); (void)r; }
                }
                break;
            }
            if (!magic || size < 8) break;
            at += size;
        }
#elif defined(__x86_64__)
        const ucontext_t *uc = (const ucontext_t *)ucontext;
        if (uc->uc_mcontext.fpregs) {
            const uint32_t *x = (const uint32_t *)uc->uc_mcontext.fpregs->_xmm;
            for (int i = 0; i < 16; ++i) {
                char xline[80];
                int len = snprintf(xline, sizeof(xline), "Guest register xmm%d=%08x%08x%08x%08x\n",
                                   i, x[i*4+3], x[i*4+2], x[i*4+1], x[i*4]);
                if (len > 0) { ssize_t r = write(fd, xline, (size_t)len); (void)r; }
            }
        }
#endif
    }
    uint8_t opcodes[64];
    uintptr_t start_rip = regs->rip >= 32 ? regs->rip - 32 : regs->rip;
    size_t count = regs->rip >= 32 ? 64 : (regs->rip ? 32 : 0);
    struct iovec l_op={opcodes,count}, r_op={(void *)start_rip,count};
    if (count && process_vm_readv(getpid(),&l_op,1,&r_op,1,0)==(ssize_t)count) {
        char oline[384];
        int pos = snprintf(oline, sizeof(oline), "Guest bytes around RIP [-32..+32]:\n  ");
        static const char hexchars[] = "0123456789abcdef";
        for (unsigned i=0; i<count; ++i) {
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
                if (pos < (int)sizeof(oline) - 4) {
                    oline[pos++] = '\n';
                    oline[pos++] = ' ';
                    oline[pos++] = ' ';
                }
            }
        }
        if (pos < (int)sizeof(oline) - 2) oline[pos++] = '\n';
        ssize_t result=write(fd,oline,(size_t)pos);(void)result;
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
