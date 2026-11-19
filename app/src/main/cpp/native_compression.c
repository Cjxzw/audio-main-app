#include <jni.h>
#include <zlib.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

/* Only already-authorized descriptors cross this boundary. No physical/virtual path parsing here. */
JNIEXPORT jlong JNICALL
Java_com_agent_voiceassistant_tools_runtime_NativeCompression_transform(
    JNIEnv *env, jobject self, jint input_fd, jint output_fd, jstring operation,
    jlong max_bytes, jlong deadline_nanos) {
    (void)self;
    const char *name = (*env)->GetStringUTFChars(env, operation, NULL);
    if (!name) return -1;
    int compressing = !strcmp(name, "gzip") || !strcmp(name, "deflate");
    int gzip_container = !strcmp(name, "gzip") || !strcmp(name, "gunzip");
    z_stream stream = {0};
    int status = compressing
        ? deflateInit2(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, gzip_container ? 31 : -15, 8, Z_DEFAULT_STRATEGY)
        : inflateInit2(&stream, gzip_container ? 31 : -15);
    int initialized = status == Z_OK;
    const char *reason = initialized ? NULL : "zlib_init";
    unsigned char in[16384], out[16384];
    int eof = 0;
    int64_t total = 0;
    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
    jmethodID current = (*env)->GetStaticMethodID(env, thread_class, "currentThread", "()Ljava/lang/Thread;");
    jmethodID interrupted = (*env)->GetMethodID(env, thread_class, "isInterrupted", "()Z");
    jobject thread = (*env)->CallStaticObjectMethod(env, thread_class, current);
    while (!reason) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t nanos = (int64_t)now.tv_sec * 1000000000 + now.tv_nsec;
        if (nanos >= deadline_nanos) { reason = "runtime_timeout"; break; }
        if ((*env)->CallBooleanMethod(env, thread, interrupted)) { reason = "runtime_cancelled"; break; }
        if (!stream.avail_in && !eof) {
            ssize_t count;
            do { count = read(input_fd, in, sizeof(in)); } while (count < 0 && errno == EINTR);
            if (count < 0) { reason = "input_read_failed"; break; }
            eof = count == 0;
            stream.next_in = in;
            stream.avail_in = (uInt)count;
        }
        stream.next_out = out;
        stream.avail_out = sizeof(out);
        status = compressing ? deflate(&stream, eof ? Z_FINISH : Z_NO_FLUSH) : inflate(&stream, Z_NO_FLUSH);
        size_t produced = sizeof(out) - stream.avail_out;
        if (total + (int64_t)produced > max_bytes) { reason = "output_limit_exceeded"; break; }
        size_t offset = 0;
        while (offset < produced) {
            ssize_t count = write(output_fd, out + offset, produced - offset);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { reason = "output_write_failed"; break; }
            offset += (size_t)count;
        }
        if (reason) break;
        total += (int64_t)produced;
        if (status == Z_STREAM_END) {
            if (!compressing) {
                unsigned char extra;
                ssize_t count;
                do { count = read(input_fd, &extra, 1); } while (count < 0 && errno == EINTR);
                if (stream.avail_in || count != 0) reason = "trailing_data_or_multiple_members";
            }
            break;
        }
        if (status != Z_OK && status != Z_BUF_ERROR) { reason = "invalid_stream"; break; }
        if (eof && !produced && !stream.avail_in) { reason = "truncated_stream"; break; }
    }
    if (initialized) { if (compressing) deflateEnd(&stream); else inflateEnd(&stream); }
    (*env)->DeleteLocalRef(env, thread);
    (*env)->DeleteLocalRef(env, thread_class);
    if (reason) {
        char message[256];
        snprintf(message, sizeof(message), "%s: %s (zlib=%d)", name, reason, status);
        jclass error_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
        (*env)->ThrowNew(env, error_class, message);
        (*env)->DeleteLocalRef(env, error_class);
    }
    (*env)->ReleaseStringUTFChars(env, operation, name);
    return reason ? -1 : total;
}
