// Host simulation of the callback/dispatcher boundary and cross-worker migration.
#define __ANDROID__
#define GUEST_CPU_TEST_FEX
#include <cassert>
#include <thread>
#include "../src/guest_cpu.h"
struct GuestFiberCpuState { unsigned id; uint64_t rbp; };
#include "../gpu/shim/gow3_fiber_bionic.cpp"
using namespace Libraries::Fiber;
static std::atomic<unsigned> allocated{}, disposed{}, entries{};
static thread_local GuestFiberCpuState* pending;
static thread_local void (*release_pending)(void*);
static thread_local void* previous_pending;
static thread_local unsigned host_id;
static unsigned migrated_id;
extern "C" GuestFiberCpuState* guest_cpu_fiber_create() {
    return new GuestFiberCpuState{++allocated,0};
}
extern "C" void guest_cpu_fiber_dispose(GuestFiberCpuState* p) { ++disposed; delete p; }
extern "C" void guest_cpu_fiber_save(GuestFiberCpuState* p) { p->rbp=host_id; }
extern "C" void guest_cpu_fiber_entry(GuestFiberCpuState* p,uintptr_t,uintptr_t,uint64_t,uint64_t,void*,int) {
    ++entries; p->rbp=1234;
}
extern "C" void guest_cpu_fiber_switch(GuestFiberCpuState* p,void (*release)(void*),void* previous) {
    assert(!pending); pending=p; release_pending=release; previous_pending=previous;
    if(previous) assert(static_cast<OrbisFiber*>(previous)->state==FiberState::Run);
}
extern "C" uint64_t guest_cpu_fiber_frame_pointer(const GuestFiberCpuState* p) { return p->rbp; }
static thread_local void* fiber_slot;
extern "C" void** runtime_thread_fiber_slot(void) { return &fiber_slot; }
extern "C" void* runtime_low_map(size_t size,int protection) {
    void* p=mmap(nullptr,size,protection,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    return p==MAP_FAILED ? nullptr : p;
}
void Gow3KeepWritable(const void*,uint64_t,bool) {}
static unsigned Dispatch() {
    assert(pending); unsigned id=pending->id;
    auto release=release_pending; auto previous=previous_pending;
    pending=nullptr; release_pending=nullptr; previous_pending=nullptr;
    if(release) release(previous);
    return id;
}
static void PS4_SYSV_ABI Entry(u64,u64) {}
int main() {
    OrbisFiber f{}, target{};
    assert(sceFiberInitialize(&f,"migrating",Entry,0,nullptr,0,nullptr,0)==0);
    assert(sceFiberInitialize(&target,"target",Entry,0,nullptr,0,nullptr,0)==0);
    u64 resumed=0;
    std::thread a([&] {
        host_id=101;
        u64 outgoing=0;
        assert(sceFiberRun(&f,1,&outgoing)==0);
        migrated_id=Dispatch();
        std::thread competing([&] {
            alignas(16) u64 other_stack[128];
            assert(sceFiberRunImpl(&f,other_stack,sizeof(other_stack),0,nullptr)==ORBIS_FIBER_ERROR_STATE);
            assert(f.addr_context==nullptr);
        }); competing.join();
        u64 rbp=0; assert(sceFiberGetThreadFramePointerAddress(&rbp)==0 && rbp==101);
        assert(sceFiberReturnToThread(7,&resumed)==0);
        assert(f.state==FiberState::Run && outgoing==7);
        Dispatch(); assert(f.state==FiberState::Idle);
    }); a.join();
    assert(entries==1 && disposed==1); // only thread A's root, never its suspended fiber
    std::thread b([&] {
        host_id=202; u64 outgoing=0;
        assert(sceFiberRun(&f,42,&outgoing)==0);
        assert(Dispatch()==migrated_id && resumed==42 && entries==1);
        u64 rbp=0; assert(sceFiberGetThreadFramePointerAddress(&rbp)==0 && rbp==202);
        assert(sceFiberSwitch(&target,3,nullptr)==0);
        assert(f.state==FiberState::Run && target.state==FiberState::Run);
        Dispatch(); assert(f.state==FiberState::Idle);
        assert(sceFiberFinalize(&f)==0);
        assert(sceFiberReturnToThread(0,nullptr)==0);
        assert(target.state==FiberState::Run);
        Dispatch(); assert(target.state==FiberState::Idle);
    }); b.join();
    assert(sceFiberFinalize(&target)==0);
    assert(allocated==disposed);
    alignas(16) u64 stack[128];
    assert(sceFiberStartContextSizeCheck(0)==0);
    assert(sceFiberInitialize(&f,"stackcheck",Entry,0,stack,sizeof(stack),nullptr,0)==0);
    OrbisFiberInfo info{}; info.size=sizeof(info);
    assert(sceFiberGetInfo(&f,&info)==0 && info.size_context_margin==sizeof(stack)-8);
    assert(sceFiberFinalize(&f)==0);
    assert(sceFiberStopContextSizeCheck()==0);
    std::puts("bionic-fiber-migration-test ok");
}
