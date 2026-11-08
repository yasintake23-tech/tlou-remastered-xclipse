#define _GNU_SOURCE
#include "../src/crash_guest_dump.h"
#include <assert.h>
#include <signal.h>
#include <sys/wait.h>
#include <stdlib.h>

int main(void) {
    int fds[2];assert(pipe(fds)==0);
    pid_t child=fork();assert(child>=0);
    if (!child) {
        close(fds[0]);alarm(2);
        GuestRegs regs={0};
        uintptr_t caller[2]={0,0x92c961+0x400000};
        uint8_t dummy_code[64];
        for (int i=0; i<64; ++i) dummy_code[i] = (uint8_t)(0x10 + i);
        regs.rip = (uintptr_t)&dummy_code[32];
        regs.has_xmm = 1;
        regs.xmm[0][0] = 0x1122334455667788ULL;
        regs.xmm[0][1] = 0x99aabbccddeeff00ULL;
        regs.gpr[GUEST_RBX]=0x20;regs.gpr[GUEST_RCX]=0;
        regs.gpr[GUEST_RBP]=(uintptr_t)caller;
        uintptr_t probe[2];
        struct iovec local={probe,sizeof(probe)},remote={caller,sizeof(caller)};
        int can_read=process_vm_readv(getpid(),&local,1,&remote,1,0)==(ssize_t)sizeof(probe);
        if (!can_read) { const char note[]="frame-read-unavailable\n";ssize_t r=write(fds[1],note,sizeof(note)-1);(void)r; }
        dump_guest_fault_regs(fds[1],&regs,0x400000,NULL);
        regs.gpr[GUEST_RBP]=1; // unreadable frame must terminate the walk safely
        regs.rip=0x1000;       // unmapped RIP must log error without fake bytes or secondary fault
        dump_guest_fault_regs(fds[1],&regs,0x400000,NULL);
        _exit(139);
    }
    close(fds[1]);char text[8192];size_t used=0;ssize_t n;
    while ((n=read(fds[0],text+used,sizeof(text)-used-1))>0) used+=(size_t)n;
    text[used]=0;close(fds[0]);int status;assert(waitpid(child,&status,0)==child);
    assert(WIFEXITED(status) && WEXITSTATUS(status)==139);
    assert(strstr(text,"Guest register rbx=0000000000000020"));
    assert(strstr(text,"Guest register xmm0=99aabbccddeeff001122334455667788 (GUEST XMM)"));
    assert(strstr(text,"Guest opcodes around RIP"));
    assert(strstr(text,"Hex: <memory unmapped or read error - no synthetic bytes generated>"));
    if (!strstr(text,"frame-read-unavailable")) assert(strstr(text,"image-relative=000000000092c961"));
    return 0;
}
