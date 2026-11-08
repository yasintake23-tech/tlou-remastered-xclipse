// SPDX-License-Identifier: GPL-2.0-or-later
// libSceFiber for TLOU on Android ARM64: x86-64 execution state is transferred by
// the embedded FEXCore dispatcher at the boundary of the guest->host callback.
// The PC implementation remains in gow3_fiber.cpp and gow3_fiber_context.cpp.
#ifdef __ANDROID__
#include "gow3_fiber.h"
#include "gow3_fiber_error.h"
#include "../../src/guest_cpu.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <sys/mman.h>
#include <unordered_map>
#include <memory>
#include <mutex>

extern "C" void* runtime_low_map(size_t size, int protection);
namespace Libraries::Fiber {

constexpr u32 Signature0=0xdef1649c, Signature1=0xb37592a0;
constexpr u32 OptionSignature=0xbb40e64d;
constexpr u64 StackSignature=0x7149f2ca7149f2caULL;
constexpr u64 StackSizeCheck=0xdeadbeefdeadbeefULL;
constexpr size_t FallbackStack=512*1024;
std::atomic<u32> context_check{0};

struct Record {
    GuestFiberCpuState* cpu{};
    void* allocated_stack{};
    size_t allocated_size{};
    u64* incoming_argument{};
    bool has_continuation{};
    ~Record() {
        if (cpu) guest_cpu_fiber_dispose(cpu);
        if (allocated_stack) munmap(allocated_stack,allocated_size);
    }
};

struct Session {
    OrbisFiber* active{};
    GuestFiberCpuState* root{};
    u64* root_return{};
    ~Session() {
        if (root) guest_cpu_fiber_dispose(root);
    }
};
thread_local Session session;
// Fibers belong to the process and may resume on a different job worker.
std::mutex records_mutex;
std::unordered_map<OrbisFiber*,std::unique_ptr<Record>> records;
void EraseRecord(OrbisFiber* f) {
    std::lock_guard lock(records_mutex);
    records.erase(f);
}
void ReleasePrevious(void* previous) {
    static_cast<OrbisFiber*>(previous)->state.store(FiberState::Idle,std::memory_order_release);
}

bool Valid(const OrbisFiber* f) {
    return f && f->magic_start==Signature0 && f->magic_end==Signature1;
}

bool Attach(OrbisFiber* f,void* memory,u64 bytes) {
    if ((!memory && bytes) || (memory && !bytes) || (reinterpret_cast<uintptr_t>(memory)&15)
        || (bytes && bytes<ORBIS_FIBER_CONTEXT_MINIMUM_SIZE) || (bytes&15)
        || f->addr_context) return false;
    f->addr_context=memory;
    f->size_context=bytes;
    f->context_start=memory;
    f->context_end=memory ? static_cast<u8*>(memory)+bytes : nullptr;
    if(memory) {
        Gow3KeepWritable(memory,bytes,true);
        if(f->flags&FiberFlags::ContextSizeCheck)
            std::fill_n(static_cast<u64*>(memory),bytes/sizeof(u64),StackSizeCheck);
        *static_cast<u64*>(memory)=StackSignature;
    }
    return true;
}

Record& RecordFor(OrbisFiber* f) {
    std::lock_guard lock(records_mutex);
    auto& record=records[f];
    if(!record) record=std::make_unique<Record>();
    return *record;
}

void SetActiveFiber(OrbisFiber* f) {
    session.active=f;
    if (void** slot=runtime_thread_fiber_slot()) *slot=f;
}

void EntryReturned() {
    // The guest reached the return stub rather than sceFiberReturnToThread.
    auto* fiber=session.active;
    if (fiber) {
        std::fprintf(stderr,"TLOU Fiber: guest entry returned without yielding (%s)\n",fiber->name);
        fiber->state=FiberState::Terminated;
    }
    SetActiveFiber(nullptr);
    if (session.root_return) *session.root_return=ORBIS_FIBER_ERROR_STATE;
    if (session.root) guest_cpu_fiber_switch(session.root,nullptr,nullptr);
}

bool Prepare(OrbisFiber* f,u64 argument) {
    Record& r=RecordFor(f);
    if (!r.cpu) r.cpu=guest_cpu_fiber_create();
    if (!r.cpu) return false;
    if (r.has_continuation) {
        if(r.incoming_argument) *r.incoming_argument=argument;
        r.incoming_argument=nullptr;
        return true;
    }
    uintptr_t top{};
    if (f->addr_context) {
        top=reinterpret_cast<uintptr_t>(f->addr_context)+f->size_context;
    } else {
        // The PS4 permits fibers without caller-provided stacks. Keep an
        // independent low-VA guest stack so the suspended root isn't overwritten.
        r.allocated_stack=runtime_low_map(FallbackStack,PROT_READ|PROT_WRITE);
        if (!r.allocated_stack) return false;
        r.allocated_size=FallbackStack;
        top=reinterpret_cast<uintptr_t>(r.allocated_stack)+FallbackStack;
    }
    if (!top || !f->entry) return false;
    guest_cpu_fiber_entry(r.cpu,reinterpret_cast<uintptr_t>(f->entry),top,
                          f->arg_on_initialize,argument,
                          reinterpret_cast<void*>(&EntryReturned),
                          (f->flags&FiberFlags::SetFpuRegs)!=0);
    r.has_continuation=true;
    return true;
}

s32 sceFiberInitializeImpl(OrbisFiber* f,const char* name,OrbisFiberEntry entry,
                           u64 init,void* memory,u64 bytes,
                           const OrbisFiberOptParam* opt,u32 flags,u32 build_version) {
    if (!f || !name || !entry) return ORBIS_FIBER_ERROR_NULL;
    if ((reinterpret_cast<uintptr_t>(f)&7)||(reinterpret_cast<uintptr_t>(memory)&15))
        return ORBIS_FIBER_ERROR_ALIGNMENT;
    if (flags & ~(u32(FiberFlags::NoUlobjmgr))) return ORBIS_FIBER_ERROR_INVALID;
    if (opt && opt->magic!=OptionSignature) return ORBIS_FIBER_ERROR_INVALID;
    if ((!memory && bytes)||(memory && !bytes)||(bytes&15)||
        (bytes && bytes<ORBIS_FIBER_CONTEXT_MINIMUM_SIZE))
        return ORBIS_FIBER_ERROR_INVALID;
    new (f) OrbisFiber{};
    f->magic_start=Signature0;
    f->magic_end=Signature1;
    f->entry=entry;
    f->arg_on_initialize=init;
    f->flags=flags | (build_version>=0x03500000 ? u32(FiberFlags::SetFpuRegs):0u)
                   | (context_check.load()?u32(FiberFlags::ContextSizeCheck):0u);
    std::strncpy(f->name,name,ORBIS_FIBER_MAX_NAME_LENGTH);
    f->name[ORBIS_FIBER_MAX_NAME_LENGTH]=0;
    f->state=FiberState::Idle;
    if (memory && !Attach(f,memory,bytes)) return ORBIS_FIBER_ERROR_INVALID;
    EraseRecord(f);
    return ORBIS_OK;
}

s32 sceFiberInitialize(OrbisFiber* f,const char* name,OrbisFiberEntry entry,
                       u64 init,void* memory,u64 bytes,const OrbisFiberOptParam* opt,u32 ver) {
    return sceFiberInitializeImpl(f,name,entry,init,memory,bytes,opt,0,ver);
}
s32 sceFiberOptParamInitialize(OrbisFiberOptParam* opt) {
    if(!opt) return ORBIS_FIBER_ERROR_NULL;
    if(reinterpret_cast<uintptr_t>(opt)&7) return ORBIS_FIBER_ERROR_ALIGNMENT;
    opt->magic=OptionSignature;
    return ORBIS_OK;
}
s32 sceFiberFinalize(OrbisFiber* f) {
    if(!f) return ORBIS_FIBER_ERROR_NULL;
    if(!Valid(f)) return ORBIS_FIBER_ERROR_INVALID;
    auto expected=FiberState::Idle;
    if(!f->state.compare_exchange_strong(expected,FiberState::Terminated))
        return ORBIS_FIBER_ERROR_STATE;
    if(f->addr_context) Gow3KeepWritable(f->addr_context,f->size_context,false);
    EraseRecord(f);
    return ORBIS_OK;
}

s32 sceFiberRunImpl(OrbisFiber* f,void* memory,u64 bytes,u64 incoming,u64* outgoing) {
    if(!f) return ORBIS_FIBER_ERROR_NULL;
    if(!Valid(f)) return ORBIS_FIBER_ERROR_INVALID;
    if(session.active) return ORBIS_FIBER_ERROR_PERMISSION;
    auto expected=FiberState::Idle;
    if(!f->state.compare_exchange_strong(expected,FiberState::Run))
        return ORBIS_FIBER_ERROR_STATE;
    // Claim ownership before attaching a stack; another worker may be running it.
    if((memory||bytes) && !Attach(f,memory,bytes)) {
        f->state=FiberState::Idle;
        return ORBIS_FIBER_ERROR_INVALID;
    }
    if(!Prepare(f,incoming)) { f->state=FiberState::Idle; return ORBIS_FIBER_ERROR_RANGE; }
    if(!session.root) session.root=guest_cpu_fiber_create();
    guest_cpu_fiber_save(session.root);
    session.root_return=outgoing;
    SetActiveFiber(f);
    guest_cpu_fiber_switch(RecordFor(f).cpu,nullptr,nullptr);
    return ORBIS_OK;
}
s32 sceFiberRun(OrbisFiber* f,u64 incoming,u64* outgoing) {
    return sceFiberRunImpl(f,nullptr,0,incoming,outgoing);
}

s32 sceFiberSwitchImpl(OrbisFiber* target,void* memory,u64 bytes,u64 incoming,u64* next) {
    if(!target) return ORBIS_FIBER_ERROR_NULL;
    if(!Valid(target)) return ORBIS_FIBER_ERROR_INVALID;
    if(!session.active) return ORBIS_FIBER_ERROR_PERMISSION;
    auto expected=FiberState::Idle;
    if(!target->state.compare_exchange_strong(expected,FiberState::Run))
        return ORBIS_FIBER_ERROR_STATE;
    // Claim ownership before attaching a stack; another worker may be running it.
    if((memory||bytes) && !Attach(target,memory,bytes)) {
        target->state=FiberState::Idle;
        return ORBIS_FIBER_ERROR_INVALID;
    }
    OrbisFiber* previous=session.active;
    Record& old=RecordFor(previous);
    if (!old.cpu) old.cpu=guest_cpu_fiber_create();
    guest_cpu_fiber_save(old.cpu);
    old.has_continuation=true;
    old.incoming_argument=next;
    if(!Prepare(target,incoming)) {
        target->state=FiberState::Idle;
        return ORBIS_FIBER_ERROR_RANGE;
    }
    SetActiveFiber(target);
    guest_cpu_fiber_switch(RecordFor(target).cpu,ReleasePrevious,previous);
    return ORBIS_OK;
}
s32 sceFiberSwitch(OrbisFiber* f,u64 argument,u64* previous) {
    return sceFiberSwitchImpl(f,nullptr,0,argument,previous);
}
s32 sceFiberReturnToThread(u64 argument,u64* next) {
    auto* current=session.active;
    if(!current || !session.root) return ORBIS_FIBER_ERROR_PERMISSION;
    Record& r=RecordFor(current);
    if(!r.cpu) r.cpu=guest_cpu_fiber_create();
    guest_cpu_fiber_save(r.cpu);
    r.has_continuation=true;
    r.incoming_argument=next;
    if(session.root_return) *session.root_return=argument;
    SetActiveFiber(nullptr);
    guest_cpu_fiber_switch(session.root,ReleasePrevious,current);
    return ORBIS_OK;
}
s32 sceFiberGetSelf(OrbisFiber** out) {
    if(!out) return ORBIS_FIBER_ERROR_NULL;
    if(!session.active) return ORBIS_FIBER_ERROR_PERMISSION;
    *out=session.active;
    return ORBIS_OK;
}
s32 sceFiberGetInfo(OrbisFiber* f,OrbisFiberInfo* out) {
    if(!f||!out) return ORBIS_FIBER_ERROR_NULL;
    if(!Valid(f)||out->size!=sizeof(OrbisFiberInfo)) return ORBIS_FIBER_ERROR_INVALID;
    out->entry=f->entry;
    out->arg_on_initialize=f->arg_on_initialize;
    out->addr_context=f->addr_context;
    out->size_context=f->size_context;
    std::strncpy(out->name,f->name,ORBIS_FIBER_MAX_NAME_LENGTH);
    out->name[ORBIS_FIBER_MAX_NAME_LENGTH]=0;
    out->size_context_margin=~0ULL;
    if((f->flags&FiberFlags::ContextSizeCheck) && f->addr_context) {
        u64* cursor=reinterpret_cast<u64*>(f->context_start)+1;
        auto* last=reinterpret_cast<u64*>(f->context_end);
        while(cursor<last && *cursor==StackSizeCheck) ++cursor;
        out->size_context_margin=reinterpret_cast<uintptr_t>(cursor)-
                                 reinterpret_cast<uintptr_t>(f->context_start)-8;
    }
    return ORBIS_OK;
}
s32 sceFiberStartContextSizeCheck(u32 flags) {
    if(flags) return ORBIS_FIBER_ERROR_INVALID;
    u32 previous=0;
    return context_check.compare_exchange_strong(previous,1u)?ORBIS_OK:ORBIS_FIBER_ERROR_STATE;
}
s32 sceFiberStopContextSizeCheck(void) {
    u32 previous=1;
    return context_check.compare_exchange_strong(previous,0u)?ORBIS_OK:ORBIS_FIBER_ERROR_STATE;
}
s32 sceFiberRename(OrbisFiber* f,const char* name) {
    if(!f||!name) return ORBIS_FIBER_ERROR_NULL;
    if(!Valid(f)) return ORBIS_FIBER_ERROR_INVALID;
    std::strncpy(f->name,name,ORBIS_FIBER_MAX_NAME_LENGTH);
    f->name[ORBIS_FIBER_MAX_NAME_LENGTH]=0;
    return ORBIS_OK;
}
s32 sceFiberGetThreadFramePointerAddress(u64* out) {
    if(!out) return ORBIS_FIBER_ERROR_NULL;
    if(!session.active) return ORBIS_FIBER_ERROR_PERMISSION;
    *out=guest_cpu_fiber_frame_pointer(session.root);
    return ORBIS_OK;
}
} // namespace Libraries::Fiber

extern "C" uintptr_t runtime_fiber_resolve(const char* name) {
    using namespace Libraries::Fiber;
    if (!std::strcmp(name,"hVYD7Ou2pCQ#libSceFiber")) return (uintptr_t)sceFiberInitialize;
    if (!std::strcmp(name,"7+OJIpko9RY#libSceFiber")) return (uintptr_t)sceFiberInitializeImpl;
    if (!std::strcmp(name,"asjUJJ+aa8s#libSceFiber")) return (uintptr_t)sceFiberOptParamInitialize;
    if (!std::strcmp(name,"JeNX5F-NzQU#libSceFiber")) return (uintptr_t)sceFiberFinalize;
    if (!std::strcmp(name,"a0LLrZWac0M#libSceFiber")) return (uintptr_t)sceFiberRun;
    if (!std::strcmp(name,"PFT2S-tJ7Uk#libSceFiber")) return (uintptr_t)sceFiberSwitch;
    if (!std::strcmp(name,"p+zLIOg27zU#libSceFiber")) return (uintptr_t)sceFiberGetSelf;
    if (!std::strcmp(name,"B0ZX2hx9DMw#libSceFiber")) return (uintptr_t)sceFiberReturnToThread;
    if (!std::strcmp(name,"avfGJ94g36Q#libSceFiber")) return (uintptr_t)sceFiberRunImpl;
    if (!std::strcmp(name,"ZqhZFuzKT6U#libSceFiber")) return (uintptr_t)sceFiberSwitchImpl;
    if (!std::strcmp(name,"uq2Y5BFz0PE#libSceFiber")) return (uintptr_t)sceFiberGetInfo;
    if (!std::strcmp(name,"Lcqty+QNWFc#libSceFiber")) return (uintptr_t)sceFiberStartContextSizeCheck;
    if (!std::strcmp(name,"Kj4nXMpnM8Y#libSceFiber")) return (uintptr_t)sceFiberStopContextSizeCheck;
    if (!std::strcmp(name,"JzyT91ucGDc#libSceFiber")) return (uintptr_t)sceFiberRename;
    if (!std::strcmp(name,"0dy4JtMUcMQ#libSceFiber")) return (uintptr_t)sceFiberGetThreadFramePointerAddress;
    return 0;
}
#endif // __ANDROID__
