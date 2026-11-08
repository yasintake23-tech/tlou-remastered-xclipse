/* Linux rwlocks behind PS4 pointer-to-handle ABI. The registry protects
   lifetime, static initialization and ownership; waiting happens outside it. */
#define _GNU_SOURCE
#include "runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <errno.h>
#include <limits.h>
typedef struct Holder {
    void *owner;
    pthread_t thread;
    unsigned readers, writer, pending;
    struct Holder *next;
} Holder;
typedef struct Rwlock {
    pthread_cond_t cond;
    Holder *holders;
    unsigned active_readers;
    int active_writer;
    unsigned inflight;
    struct Rwlock *next;
} Rwlock;
typedef struct { int64_t seconds, nanoseconds; } GuestTime;
static pthread_mutex_t registry_lock = PTHREAD_MUTEX_INITIALIZER;
static Rwlock *registry;
static size_t created, reads, writes, unlocks;
static void *current_owner(void) {
#ifdef __ANDROID__
    void **slot=runtime_thread_fiber_slot();
    if (slot && *slot) return *slot;
#endif
    return (void *)(uintptr_t)pthread_self();
}
static int holder_matches(const Holder *h, void *owner) {
#ifdef __ANDROID__
    void **slot=runtime_thread_fiber_slot();
    if (slot && *slot) return h->owner==owner;
#endif
    return h->owner==owner || pthread_equal(h->thread,pthread_self());
}
static int32_t error(int e) {
    if (!e) return 0;
    unsigned code;
    switch(e) {
    case EPERM: code=1; break;
    case EDEADLK: code=11; break;
    case ENOMEM: code=12; break;
    case EBUSY: code=16; break;
    case EINVAL: code=22; break;
    case EAGAIN: code=35; break;
    case ETIMEDOUT: code=60; break;
    default: fprintf(stderr,"STOP: unmapped rwlock error %d\n",e); exit(21);
    }
    return (int32_t)(UINT32_C(0x80020000)|code);
}
/* All helpers ending in _locked require registry_lock. */
static Rwlock *find_locked(Rwlock *handle) {
    for (Rwlock *r=registry; r; r=r->next) if (r==handle) return r;
    return NULL;
}
static int create_locked(Rwlock **out) {
    Rwlock *r=calloc(1,sizeof(*r));
    if (!r) return ENOMEM;
    int e=pthread_cond_init(&r->cond,NULL);
    if (e) { free(r); return e; }
    r->next=registry; registry=r; *out=r; ++created; return 0;
}
static ABI int32_t rw_init(Rwlock **out, void *const *attr, const char *name) {
    (void)name;
    if (!out || (attr && !*attr)) return error(EINVAL);
    if (attr) { fputs("STOP: non-default PS4 rwlock attributes are not implemented\n",stderr); exit(21); }
    pthread_mutex_lock(&registry_lock);
    /* POSIX allows uninitialized storage, so never read *out during init. */
    int e=create_locked(out);
    pthread_mutex_unlock(&registry_lock); return error(e);
}
static void prune_locked(Rwlock *r, Holder *h) {
    if (h->readers || h->writer || h->pending) return;
    Holder **link=&r->holders;
    while (*link!=h) link=&(*link)->next;
    *link=h->next; free(h);
}
enum Operation { READ, WRITE, TRY_READ, TRY_WRITE, TIMED_READ, TIMED_WRITE };
static int32_t acquire(Rwlock **handle, enum Operation op, const GuestTime *time) {
    if (!handle) return error(EINVAL);
    int writer=op==WRITE || op==TRY_WRITE || op==TIMED_WRITE;
    int attempt=op==TRY_READ || op==TRY_WRITE;
    int timed=op==TIMED_READ || op==TIMED_WRITE;
    void *owner=current_owner();
    pthread_mutex_lock(&registry_lock);
    int e=0;
    if (!*handle) e=create_locked(handle);
    Rwlock *r=e ? NULL : find_locked(*handle);
    if (!r) { pthread_mutex_unlock(&registry_lock); return error(e ? e : EINVAL); }
    Holder *h=r->holders;
    while (h && !holder_matches(h,owner)) h=h->next;
    if (h && (h->writer || (writer && h->readers))) {
        pthread_mutex_unlock(&registry_lock); return error(attempt ? EBUSY : EDEADLK);
    }
    if (h && h->readers==UINT_MAX) { pthread_mutex_unlock(&registry_lock); return error(EAGAIN); }
    if (!h) {
        h=calloc(1,sizeof(*h));
        if (!h) { pthread_mutex_unlock(&registry_lock); return error(ENOMEM); }
        h->owner=owner; h->thread=pthread_self(); h->next=r->holders; r->holders=h;
    }
    /* Reader-preferring semantics (matching glibc default): readers only wait while a writer
       actively holds the lock, never behind a queued writer. Timed operations first try the
       lock, matching POSIX timeout validation. */
    int busy=writer ? (r->active_writer || r->active_readers!=0) : r->active_writer;
    if (busy) {
        if (attempt) {
            prune_locked(r,h);
            pthread_mutex_unlock(&registry_lock);
            return error(EBUSY);
        }
        struct timespec deadline={0,0};
        if (timed) {
            if (!time || time->nanoseconds<0 || time->nanoseconds>=1000000000) {
                prune_locked(r,h);
                pthread_mutex_unlock(&registry_lock);
                return error(EINVAL);
            }
            deadline.tv_sec=(time_t)time->seconds;
            deadline.tv_nsec=(long)time->nanoseconds;
        }
        ++r->inflight; ++h->pending;
        while (writer ? (r->active_writer || r->active_readers!=0) : r->active_writer) {
            e=timed ? pthread_cond_timedwait(&r->cond,&registry_lock,&deadline)
                    : pthread_cond_wait(&r->cond,&registry_lock);
            if (e) break;
        }
        --r->inflight; --h->pending;
    }
    if (!e) {
        if (writer) { r->active_writer=1; h->writer=1; ++writes; }
        else { ++r->active_readers; ++h->readers; ++reads; }
    }
    prune_locked(r,h);
    pthread_mutex_unlock(&registry_lock); return error(e);
}
static ABI int32_t rw_read(Rwlock **r) { return acquire(r,READ,NULL); }
static ABI int32_t rw_write(Rwlock **r) { return acquire(r,WRITE,NULL); }
static ABI int32_t rw_tryread(Rwlock **r) { return acquire(r,TRY_READ,NULL); }
static ABI int32_t rw_trywrite(Rwlock **r) { return acquire(r,TRY_WRITE,NULL); }
static ABI int32_t rw_timedread(Rwlock **r,const GuestTime *t) { return acquire(r,TIMED_READ,t); }
static ABI int32_t rw_timedwrite(Rwlock **r,const GuestTime *t) { return acquire(r,TIMED_WRITE,t); }
static ABI int32_t rw_unlock(Rwlock **handle) {
    if (!handle) return error(EINVAL);
    void *owner=current_owner();
    pthread_mutex_lock(&registry_lock);
    Rwlock *r=find_locked(*handle);
    if (!r) { pthread_mutex_unlock(&registry_lock); return error(EINVAL); }
    Holder *h=r->holders;
    while (h && !holder_matches(h,owner)) h=h->next;
    if (!h || (!h->readers && !h->writer)) {
        /* Fallback if a lock crossed a thread/fiber boundary without slot match. */
        h=r->holders;
        while (h && !pthread_equal(h->thread,pthread_self())) h=h->next;
    }
    if (!h || (!h->readers && !h->writer)) { pthread_mutex_unlock(&registry_lock); return error(EPERM); }
    if (h->writer) {
        h->writer=0;
        r->active_writer=0;
        pthread_cond_broadcast(&r->cond);
    } else {
        --h->readers;
        if (!--r->active_readers) pthread_cond_broadcast(&r->cond);
    }
    ++unlocks;
    prune_locked(r,h);
    pthread_mutex_unlock(&registry_lock); return 0;
}
static ABI int32_t rw_destroy(Rwlock **handle) {
    if (!handle) return error(EINVAL);
    pthread_mutex_lock(&registry_lock);
    if (!*handle) { pthread_mutex_unlock(&registry_lock); return 0; }
    Rwlock *r=find_locked(*handle);
    if (!r) { pthread_mutex_unlock(&registry_lock); return error(EINVAL); }
    if (r->inflight || r->holders || r->active_readers || r->active_writer) {
        pthread_mutex_unlock(&registry_lock); return error(EBUSY);
    }
    int e=pthread_cond_destroy(&r->cond);
    if (!e) {
        Rwlock **link=&registry;
        while (*link!=r) link=&(*link)->next;
        *link=r->next; free(r); *handle=(Rwlock *)(uintptr_t)1;
    }
    pthread_mutex_unlock(&registry_lock); return error(e);
}
uintptr_t runtime_rwlock_resolve(const char *name) {
    if (!strcmp(name,"6ULAa0fq4jA#libkernel")) return (uintptr_t)rw_init;
    if (!strcmp(name,"BB+kb08Tl9A#libkernel")) return (uintptr_t)rw_destroy;
    if (!strcmp(name,"Ox9i0c7L5w0#libkernel")) return (uintptr_t)rw_read;
    if (!strcmp(name,"mqdNorrB+gI#libkernel")) return (uintptr_t)rw_write;
    if (!strcmp(name,"XD3mDeybCnk#libkernel")) return (uintptr_t)rw_tryread;
    if (!strcmp(name,"bIHoZCTomsI#libkernel")) return (uintptr_t)rw_trywrite;
    if (!strcmp(name,"+L98PIbGttk#libkernel")) return (uintptr_t)rw_unlock;
    if (!strcmp(name,"iPtZRWICjrM#libkernel")) return (uintptr_t)rw_timedread;
    if (!strcmp(name,"adh--6nIqTk#libkernel")) return (uintptr_t)rw_timedwrite;
    return 0;
}
void runtime_rwlock_report(void) {
    pthread_mutex_lock(&registry_lock);
    printf("Runtime: rwlocks created=%zu, reads=%zu, writes=%zu, unlocks=%zu\n",created,reads,writes,unlocks);
    pthread_mutex_unlock(&registry_lock);
}

