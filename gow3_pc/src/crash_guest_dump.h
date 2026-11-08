/* Bounded Linux/Bionic guest diagnostics for fatal signal context. */
#ifndef TLOU_CRASH_GUEST_DUMP_H
#define TLOU_CRASH_GUEST_DUMP_H
#include "guest_cpu.h"
#include <stdint.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

static inline void crash_hex(char *out, uint64_t value) {
    static const char digits[] = "0123456789abcdef";
    for (unsigned i=0;i<16;++i) out[i]=digits[(value >> (60-i*4)) & 15];
}
static inline void dump_guest_fault_regs(int fd, const GuestRegs *regs, uintptr_t image_base) {
    static const char names[GUEST_GPRS][4]={"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi",
                                           "r8 ","r9 ","r10","r11","r12","r13","r14","r15"};
    for (unsigned i=0;i<GUEST_GPRS;++i) {
        char line[]="Guest register rax=0000000000000000\n";
        memcpy(line+15,names[i],3);crash_hex(line+19,regs->gpr[i]);
        ssize_t result=write(fd,line,sizeof(line)-1);(void)result;
    }
    uint8_t opcodes[16];
    struct iovec l_op={opcodes,sizeof(opcodes)}, r_op={(void *)regs->rip,sizeof(opcodes)};
    if (regs->rip && process_vm_readv(getpid(),&l_op,1,&r_op,1,0)==(ssize_t)sizeof(opcodes)) {
        char oline[72] = "Guest bytes at RIP: ";
        size_t pos = 20;
        static const char hexchars[] = "0123456789abcdef";
        for (unsigned i=0;i<16;++i) {
            oline[pos++] = hexchars[(opcodes[i] >> 4) & 0xf];
            oline[pos++] = hexchars[opcodes[i] & 0xf];
            oline[pos++] = ' ';
        }
        oline[pos-1] = '\n';
        ssize_t result=write(fd,oline,pos);(void)result;
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
