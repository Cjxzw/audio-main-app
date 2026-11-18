// Path aliases for trusted, dynamically linked Android programs. Not a sandbox.
#define _GNU_SOURCE
#undef _FORTIFY_SOURCE
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <utime.h>
#include <unistd.h>

static const char *map_path(const char *path, char *buffer) {
    if (!path || path[0] != '/') return path;
    const char *aliases[] = {"/workspace", "/source", "/logs"};
    const char *vars[] = {"HANWO_WORKSPACE", "HANWO_SOURCE", "HANWO_LOGS"};
    for (int i = 0; i < 3; i++) {
        size_t n = strlen(aliases[i]);
        if (strncmp(path, aliases[i], n) || (path[n] && path[n] != '/')) continue;
        const char *root = getenv(vars[i]);
        if (!root) return path;
        if (snprintf(buffer, PATH_MAX, "%s%s", root, path + n) >= PATH_MAX) return path;
        return buffer;
    }
    return path;
}

#define REAL(name) __typeof__(&name) fn = (__typeof__(&name))dlsym(RTLD_NEXT, #name)
#define MAP(arg) char arg##_buf[PATH_MAX]; arg = map_path(arg, arg##_buf)
#define ONE_INT(name) int name(const char *path) { MAP(path); REAL(name); return fn(path); }
#define TWO_PATH(name) int name(const char *old, const char *next) { MAP(old); MAP(next); REAL(name); return fn(old, next); }

int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list a; va_start(a, flags); mode = (mode_t)va_arg(a, int); va_end(a); }
    MAP(path); REAL(open); return fn(path, flags, mode);
}
int open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list a; va_start(a, flags); mode = (mode_t)va_arg(a, int); va_end(a); }
    MAP(path); REAL(open64); return fn(path, flags, mode);
}
int openat(int dir, const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list a; va_start(a, flags); mode = (mode_t)va_arg(a, int); va_end(a); }
    MAP(path); REAL(openat); return fn(dir, path, flags, mode);
}
int openat64(int dir, const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list a; va_start(a, flags); mode = (mode_t)va_arg(a, int); va_end(a); }
    MAP(path); REAL(openat64); return fn(dir, path, flags, mode);
}
int __open_2(const char *path, int flags) { return open(path, flags); }
int __openat_2(int dir, const char *path, int flags) { return openat(dir, path, flags); }
int creat(const char *path, mode_t mode) { return open(path, O_CREAT | O_WRONLY | O_TRUNC, mode); }
FILE *fopen(const char *path, const char *mode) { MAP(path); REAL(fopen); return fn(path, mode); }
FILE *fopen64(const char *path, const char *mode) { MAP(path); REAL(fopen64); return fn(path, mode); }
FILE *freopen(const char *path, const char *mode, FILE *stream) { MAP(path); REAL(freopen); return fn(path, mode, stream); }
DIR *opendir(const char *path) { MAP(path); REAL(opendir); return fn(path); }
int stat(const char *path, struct stat *info) { MAP(path); REAL(stat); return fn(path, info); }
int lstat(const char *path, struct stat *info) { MAP(path); REAL(lstat); return fn(path, info); }
int stat64(const char *path, struct stat64 *info) { MAP(path); REAL(stat64); return fn(path, info); }
int lstat64(const char *path, struct stat64 *info) { MAP(path); REAL(lstat64); return fn(path, info); }
int fstatat(int dir, const char *path, struct stat *info, int flags) { MAP(path); REAL(fstatat); return fn(dir, path, info, flags); }
int fstatat64(int dir, const char *path, struct stat64 *info, int flags) { MAP(path); REAL(fstatat64); return fn(dir, path, info, flags); }
int access(const char *path, int mode) { MAP(path); REAL(access); return fn(path, mode); }
int faccessat(int dir, const char *path, int mode, int flags) { MAP(path); REAL(faccessat); return fn(dir, path, mode, flags); }
int mkdir(const char *path, mode_t mode) { MAP(path); REAL(mkdir); return fn(path, mode); }
int mkdirat(int dir, const char *path, mode_t mode) { MAP(path); REAL(mkdirat); return fn(dir, path, mode); }
int chmod(const char *path, mode_t mode) { MAP(path); REAL(chmod); return fn(path, mode); }
int fchmodat(int dir, const char *path, mode_t mode, int flags) { MAP(path); REAL(fchmodat); return fn(dir, path, mode, flags); }
int chown(const char *path, uid_t owner, gid_t group) { MAP(path); REAL(chown); return fn(path, owner, group); }
int lchown(const char *path, uid_t owner, gid_t group) { MAP(path); REAL(lchown); return fn(path, owner, group); }
int fchownat(int dir, const char *path, uid_t owner, gid_t group, int flags) { MAP(path); REAL(fchownat); return fn(dir, path, owner, group, flags); }
int utime(const char *path, const struct utimbuf *times) { MAP(path); REAL(utime); return fn(path, times); }
int utimes(const char *path, const struct timeval times[2]) { MAP(path); REAL(utimes); return fn(path, times); }
int lutimes(const char *path, const struct timeval times[2]) { MAP(path); REAL(lutimes); return fn(path, times); }
int utimensat(int dir, const char *path, const struct timespec times[2], int flags) { MAP(path); REAL(utimensat); return fn(dir, path, times, flags); }
ONE_INT(chdir)
ONE_INT(unlink)
ONE_INT(rmdir)
ONE_INT(remove)
TWO_PATH(rename)
TWO_PATH(link)
int unlinkat(int dir, const char *path, int flags) { MAP(path); REAL(unlinkat); return fn(dir, path, flags); }
int renameat(int dir, const char *old, int next_dir, const char *next) { MAP(old); MAP(next); REAL(renameat); return fn(dir, old, next_dir, next); }
int symlink(const char *old, const char *next) { MAP(old); MAP(next); REAL(symlink); return fn(old, next); }
int symlinkat(const char *old, int dir, const char *next) { MAP(old); MAP(next); REAL(symlinkat); return fn(old, dir, next); }
int linkat(int old_dir, const char *old, int next_dir, const char *next, int flags) { MAP(old); MAP(next); REAL(linkat); return fn(old_dir, old, next_dir, next, flags); }
ssize_t readlink(const char *path, char *out, size_t len) { MAP(path); REAL(readlink); return fn(path, out, len); }
ssize_t readlinkat(int dir, const char *path, char *out, size_t len) { MAP(path); REAL(readlinkat); return fn(dir, path, out, len); }
char *realpath(const char *path, char *resolved) { MAP(path); REAL(realpath); return fn(path, resolved); }
int execve(const char *path, char *const argv[], char *const env[]) { MAP(path); REAL(execve); return fn(path, argv, env); }
int statvfs(const char *path, struct statvfs *info) { MAP(path); REAL(statvfs); return fn(path, info); }
int truncate(const char *path, off_t length) { MAP(path); REAL(truncate); return fn(path, length); }
