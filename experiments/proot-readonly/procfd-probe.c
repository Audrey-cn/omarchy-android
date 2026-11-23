#define _GNU_SOURCE
/* Diagnostic only: never linked into the app or used to emulate file access. */
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <unistd.h>
#if defined(__linux__)
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#endif

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 1U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 2U
#endif

#if defined(__linux__)
static void print_text_file(const char *path, int status_only) {
    char line[1024];
    FILE *stream = fopen(path, "r");
    if (!stream) {
        printf("metadata path=%s errno=%d\n", path, errno);
        return;
    }
    while (fgets(line, sizeof(line), stream)) {
        if (!status_only || !strncmp(line, "Uid:", 4) ||
            !strncmp(line, "Gid:", 4) || !strncmp(line, "TracerPid:", 10) ||
            !strncmp(line, "NoNewPrivs:", 11) || !strncmp(line, "Seccomp:", 8))
            printf("metadata path=%s %s", path, line);
    }
    fclose(stream);
}

static void print_fd(const char *kind, int fd) {
    struct stat info;
    char path[128], target[512];
    int flags = fcntl(fd, F_GETFL), flags_errno = flags < 0 ? errno : 0;
    int descriptor_flags = fcntl(fd, F_GETFD);
    printf("fd kind=%s fd=%d flags=%#x mode=%d flags_errno=%d cloexec=%d\n",
           kind, fd, flags, flags < 0 ? -1 : flags & O_ACCMODE, flags_errno,
           descriptor_flags < 0 ? -1 : !!(descriptor_flags & FD_CLOEXEC));
    if (!fstat(fd, &info))
        printf("stat kind=%s dev=%ju ino=%ju uid=%ju gid=%ju perms=%#o size=%jd\n",
               kind, (uintmax_t)info.st_dev, (uintmax_t)info.st_ino,
               (uintmax_t)info.st_uid, (uintmax_t)info.st_gid,
               (unsigned)(info.st_mode & 07777), (intmax_t)info.st_size);
    else
        printf("stat kind=%s errno=%d\n", kind, errno);
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t length = readlink(path, target, sizeof(target) - 1);
    if (length >= 0) {
        target[length] = 0;
        printf("target kind=%s value=%s\n", kind, target);
    } else {
        printf("target kind=%s errno=%d\n", kind, errno);
    }
#if defined(__linux__)
    length = fgetxattr(fd, "security.selinux", target, sizeof(target) - 1);
    if (length >= 0) {
        target[length] = 0;
        printf("selinux kind=%s label=%s\n", kind, target);
    } else {
        printf("selinux kind=%s errno=%d\n", kind, errno);
    }
#endif
    snprintf(path, sizeof(path), "/proc/self/fdinfo/%d", fd);
    print_text_file(path, 0);
}

/* All attempted writes affect only this process's disposable test allocation. */
static int inspect_reopen(const char *kind, const char *route, int original,
                          int reopened, int open_errno) {
    if (reopened < 0) {
        printf("reopen kind=%s route=%s result=denied errno=%d\n",
               kind, route, open_errno);
        return 0;
    }
    int flags = fcntl(reopened, F_GETFL);
    int flags_errno = flags < 0 ? errno : 0;
    int descriptor_flags = fcntl(reopened, F_GETFD);
    struct stat source, target;
    int same = !fstat(original, &source) && !fstat(reopened, &target) &&
               source.st_dev == target.st_dev && source.st_ino == target.st_ino;
    char byte;
    errno = 0;
    ssize_t read_result = pread(reopened, &byte, 1, 0);
    int read_errno = read_result < 0 ? errno : 0;
    errno = 0;
    ssize_t write_result = pwrite(reopened, "P", 1, 0);
    int write_errno = write_result < 0 ? errno : 0;
    errno = 0;
    void *mapping = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED,
                         reopened, 0);
    int mmap_errno = mapping == MAP_FAILED ? errno : 0;
    int writable_mapping = mapping != MAP_FAILED;
    if (writable_mapping)
        munmap(mapping, 4096);
    int mode = flags < 0 ? -1 : flags & O_ACCMODE;
    int secure = same && mode == O_RDONLY && descriptor_flags >= 0 &&
                 (descriptor_flags & FD_CLOEXEC) && read_result == 1 &&
                 write_result == -1 && write_errno == EBADF &&
                 !writable_mapping && mmap_errno == EACCES;
    printf("reopen kind=%s route=%s result=%s fd=%d mode=%d flags_errno=%d "
           "cloexec=%d same_inode=%d read=%zd read_errno=%d write=%zd "
           "write_errno=%d shared_rw_mmap=%d mmap_errno=%d\n",
           kind, route, secure ? "readonly_enforced" : "invalid_access",
           reopened, mode, flags_errno,
           descriptor_flags < 0 ? -1 : !!(descriptor_flags & FD_CLOEXEC), same,
           read_result, read_errno, write_result, write_errno, writable_mapping,
           mmap_errno);
    close(reopened);
    return secure;
}

static int reopen_path(const char *kind, const char *route, int source,
                       const char *path) {
    struct stat target;
    errno = 0;
    int stat_result = stat(path, &target);
    int stat_errno = stat_result ? errno : 0;
    printf("path_stat kind=%s route=%s result=%d errno=%d regular=%d\n",
           kind, route, stat_result, stat_errno,
           stat_result ? -1 : !!S_ISREG(target.st_mode));
    errno = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    int saved_errno = fd < 0 ? errno : 0;
    printf("requested kind=%s route=%s path=%s\n", kind, route, path);
    return inspect_reopen(kind, route, source, fd, saved_errno);
}

static int reopen_relative(const char *kind, const char *route, int source,
                           const char *directory) {
    char leaf[32];
    int directory_fd = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory_fd < 0) {
        printf("directory kind=%s route=%s path=%s errno=%d\n",
               kind, route, directory, errno);
        return 0;
    }
    snprintf(leaf, sizeof(leaf), "%d", source);
    errno = 0;
    int fd = openat(directory_fd, leaf, O_RDONLY | O_CLOEXEC);
    int saved_errno = fd < 0 ? errno : 0;
    printf("requested kind=%s route=%s directory=%s leaf=%s\n",
           kind, route, directory, leaf);
    int result = inspect_reopen(kind, route, source, fd, saved_errno);
    close(directory_fd);
    return result;
}

static int probe_routes(const char *kind, int fd, const char *real_path) {
    char path[128], directory[128];
    int successes = 0;
    print_fd(kind, fd);
    if (real_path)
        successes += reopen_path(kind, "real_path", fd, real_path);
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    successes += reopen_path(kind, "proc_self", fd, path);
    snprintf(path, sizeof(path), "/proc/%ld/fd/%d", (long)getpid(), fd);
    successes += reopen_path(kind, "proc_pid", fd, path);
    snprintf(path, sizeof(path), "/proc/thread-self/fd/%d", fd);
    successes += reopen_path(kind, "proc_thread_self", fd, path);
    snprintf(path, sizeof(path), "/dev/fd/%d", fd);
    successes += reopen_path(kind, "dev_fd", fd, path);
    successes += reopen_relative(kind, "openat_self", fd, "/proc/self/fd");
    snprintf(directory, sizeof(directory), "/proc/%ld/fd", (long)getpid());
    successes += reopen_relative(kind, "openat_pid", fd, directory);
    return successes;
}

static int prepare_allocation(int fd) {
    if (ftruncate(fd, 4096) || pwrite(fd, "P", 1, 0) != 1) {
        fprintf(stderr, "prepare fd=%d errno=%d\n", fd, errno);
        return -1;
    }
    return 0;
}
#endif

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
#if !defined(__linux__)
    fprintf(stderr, "This probe requires Linux/Android; no compatibility result.\n");
    return 77;
#else
    struct utsname kernel;
    printf("probe schema=1 pid=%ld ppid=%ld uid=%ju euid=%ju gid=%ju egid=%ju "
           "dumpable=%d\n", (long)getpid(), (long)getppid(),
           (uintmax_t)getuid(), (uintmax_t)geteuid(), (uintmax_t)getgid(),
           (uintmax_t)getegid(), prctl(PR_GET_DUMPABLE, 0, 0, 0, 0));
    if (!uname(&kernel))
        printf("kernel release=%s machine=%s\n", kernel.release, kernel.machine);
    print_text_file("/proc/self/status", 1);
    print_text_file("/proc/self/attr/current", 0);
    umask(077);
    char *control_path = NULL;
    const char *tmp = getenv("TMPDIR");
    if (!tmp || !*tmp) tmp = "/tmp";
    printf("private_control_directory=%s\n", tmp);
    if (asprintf(&control_path, "%s/omarchy-procfd-probe-XXXXXX", tmp) < 0)
        return 2;
    int control = mkstemp(control_path);
    if (control < 0 || prepare_allocation(control)) {
        fprintf(stderr, "control setup errno=%d\n", errno);
        if (control >= 0) close(control);
        unlink(control_path);
        free(control_path);
        return 2;
    }
    probe_routes("private_file", control, control_path);
    errno = 0;
    int fallback_readonly = open(control_path, O_RDONLY | O_CLOEXEC);
    int fallback_errno = fallback_readonly < 0 ? errno : 0;
    int unlink_result = unlink(control_path);
    int unlink_errno = unlink_result ? errno : 0;
    struct stat unlinked;
    int nlink_zero = !fstat(control, &unlinked) && unlinked.st_nlink == 0;
    printf("file_fallback unlink_result=%d unlink_errno=%d nlink_zero=%d\n",
           unlink_result, unlink_errno, nlink_zero);
    inspect_reopen("private_file", "unlinked_pair", control,
                   fallback_readonly, fallback_errno);
    close(control);
    free(control_path);
#if defined(SYS_memfd_create)
    int memory = (int)syscall(SYS_memfd_create, "omarchy-procfd-probe",
                             MFD_CLOEXEC | MFD_ALLOW_SEALING);
#else
    errno = ENOSYS;
    int memory = -1;
#endif
    if (memory < 0) {
        printf("summary memfd_create_errno=%d readonly_routes=0\n", errno);
        return 3;
    }
    if (prepare_allocation(memory)) {
        close(memory);
        return 2;
    }
    errno = 0;
    int seals_result = fcntl(memory, F_ADD_SEALS,
                             F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL);
    printf("memfd_seals result=%d errno=%d\n",
           seals_result, seals_result < 0 ? errno : 0);
    int readonly_routes = probe_routes("memfd", memory, NULL);
    close(memory);
    printf("summary readonly_routes=%d meaning=diagnostic_only\n", readonly_routes);
    return readonly_routes ? 0 : 10;
#endif
}
