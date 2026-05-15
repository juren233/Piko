#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

extern "C" {
#include "xquic/xqc_errno.h"
#include "xquic/xquic.h"
void *piko_xqc_stream_proto_data(xqc_stream_t *stream);
}

namespace {

constexpr size_t kMaxFrameBytes = 8 * 1024 * 1024;
constexpr size_t kReadBufferBytes = 64 * 1024;
constexpr uint32_t kContextMagic = 0x504b5843;
constexpr uint32_t kChannelMagic = 0x504b5848;

const char kServerKeyPem[] =
    "-----BEGIN PRIVATE KEY-----\n"
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgov0dxaIjwqJY34jI\n"
    "P0zvm97r8JRvm2Ws1giHdnapupShRANCAATNAruiTyD2S4Y6g1t1jw10SjXR3Mqi\n"
    "YWX8/P77koABvJL7zwZhf5HrbDeEsnFl7rHUlPsZH4dgBrFKUh5KtS73\n"
    "-----END PRIVATE KEY-----\n";

const char kServerCertPem[] =
    "-----BEGIN CERTIFICATE-----\n"
    "MIIBXDCCAQGgAwIBAgIUN+N6xu02oxwho1ow6FGXlT8coqEwCgYIKoZIzj0EAwIw\n"
    "HDEaMBgGA1UEAwwRUGlrbyBYUVVJQyBEaXJlY3QwHhcNMjYwNTE1MDM1MTE5WhcN\n"
    "MzYwNTEyMDM1MTE5WjAcMRowGAYDVQQDDBFQaWtvIFhRVUlDIERpcmVjdDBZMBMG\n"
    "ByqGSM49AgEGCCqGSM49AwEHA0IABM0Cu6JPIPZLhjqDW3WPDXRKNdHcyqJhZfz8\n"
    "/vuSgAG8kvvPBmF/ketsN4SycWXusdSU+xkfh2AGsUpSHkq1LvejITAfMB0GA1Ud\n"
    "DgQWBBQthNTNjDQKgI3Se0xj2CCyuYAbSTAKBggqhkjOPQQDAgNJADBGAiEAt9ES\n"
    "jFovtkGTXBZNuOemjFJHU2+gfOdUdLnWUe6C87sCIQDyu6u6KvpTD0Du+15LaV42\n"
    "LwZ9mrqMkJIKP9/bqJtc/A==\n"
    "-----END CERTIFICATE-----\n";

JavaVM *gVm = nullptr;
jmethodID gOnFrame = nullptr;
jmethodID gOnClosed = nullptr;

struct NativeContext;

struct PendingFrame {
    std::vector<uint8_t> bytes;
    size_t offset = 0;
};

struct NativeChannel {
    explicit NativeChannel(NativeContext *owner) : context(owner) {}

    uint32_t magic = kChannelMagic;
    NativeContext *context;
    xqc_cid_t cid{};
    xqc_stream_t *stream = nullptr;
    jobject receiver = nullptr;
    std::mutex mutex;
    std::vector<PendingFrame> pending;
    std::vector<uint8_t> incoming;
    std::atomic<bool> handshakeComplete{false};
    std::atomic<bool> closed{false};
    bool clientOwnedContext = false;
};

struct NativeContext {
    uint32_t magic = kContextMagic;
    bool server = false;
    int fd = -1;
    int port = 0;
    xqc_engine_t *engine = nullptr;
    jobject receiver = nullptr;
    std::mutex mutex;
    std::condition_variable cv;
    std::thread worker;
    std::atomic<bool> closed{false};
    xqc_usec_t wakeAfter = 10000;
    std::vector<std::shared_ptr<NativeChannel>> channels;
};

std::mutex gRegistryMutex;
std::unordered_map<jlong, std::shared_ptr<NativeContext>> gContexts;
std::unordered_map<jlong, std::shared_ptr<NativeChannel>> gChannels;

NativeContext *contextFromUserData(void *userData);

xqc_usec_t nowMicros() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<xqc_usec_t>(std::chrono::duration_cast<std::chrono::microseconds>(now).count());
}

std::string jString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool writeTextFile(const std::string &path, const char *content) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file.is_open()) return false;
    file << content;
    return file.good();
}

bool ensureServerCertificate(const std::string &directory, std::string *keyPath, std::string *certPath) {
    if (directory.empty()) return false;
    *keyPath = directory + "/piko-xquic-key.pem";
    *certPath = directory + "/piko-xquic-cert.pem";
    return writeTextFile(*keyPath, kServerKeyPem) && writeTextFile(*certPath, kServerCertPem);
}

void noopLog(xqc_log_level_t, const void *, size_t, void *) {}

void noopQlog(qlog_event_importance_t, const void *, size_t, void *) {}

void setTimer(xqc_usec_t wakeAfter, void *userData) {
    auto *ctx = static_cast<NativeContext *>(userData);
    if (ctx == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->wakeAfter = wakeAfter == 0 ? 1000 : wakeAfter;
    }
    ctx->cv.notify_all();
}

ssize_t writeSocketEx(uint64_t, const unsigned char *buf, size_t size, const struct sockaddr *peerAddr,
                      socklen_t peerAddrLen, void *connUserData) {
    NativeContext *ctx = contextFromUserData(connUserData);
    if (ctx == nullptr || ctx->fd < 0) return XQC_SOCKET_ERROR;
    ssize_t sent;
    do {
        sent = sendto(ctx->fd, buf, size, 0, peerAddr, peerAddrLen);
    } while (sent < 0 && errno == EINTR);
    if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return XQC_SOCKET_EAGAIN;
    return sent < 0 ? XQC_SOCKET_ERROR : sent;
}

ssize_t writeSocket(const unsigned char *buf, size_t size, const struct sockaddr *peerAddr,
                    socklen_t peerAddrLen, void *connUserData) {
    return writeSocketEx(0, buf, size, peerAddr, peerAddrLen, connUserData);
}

int serverAccept(xqc_engine_t *, xqc_connection_t *, const xqc_cid_t *, void *) {
    return XQC_OK;
}

void notifyClosed(const std::shared_ptr<NativeChannel> &channel) {
    if (!channel || channel->receiver == nullptr) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(channel->receiver, gOnClosed, reinterpret_cast<jlong>(channel.get()));
    if (attached) gVm->DetachCurrentThread();
}

void notifyFrame(const std::shared_ptr<NativeChannel> &channel, const uint8_t *data, size_t size) {
    if (!channel || channel->receiver == nullptr || size > kMaxFrameBytes) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jbyteArray frame = env->NewByteArray(static_cast<jsize>(size));
    if (frame != nullptr) {
        env->SetByteArrayRegion(frame, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
        env->CallVoidMethod(channel->receiver, gOnFrame, reinterpret_cast<jlong>(channel.get()), frame);
        env->DeleteLocalRef(frame);
    }
    if (attached) gVm->DetachCurrentThread();
}

std::shared_ptr<NativeChannel> findChannel(NativeChannel *raw) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gChannels.find(reinterpret_cast<jlong>(raw));
    return it == gChannels.end() ? nullptr : it->second;
}

void registerChannel(const std::shared_ptr<NativeChannel> &channel) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gChannels[reinterpret_cast<jlong>(channel.get())] = channel;
}

NativeChannel *channelFromUserData(void *userData) {
    if (userData == nullptr) return nullptr;
    const uint32_t magic = *static_cast<uint32_t *>(userData);
    return magic == kChannelMagic ? static_cast<NativeChannel *>(userData) : nullptr;
}

NativeContext *contextFromUserData(void *userData) {
    if (userData == nullptr) return nullptr;
    const uint32_t magic = *static_cast<uint32_t *>(userData);
    if (magic == kContextMagic) return static_cast<NativeContext *>(userData);
    if (magic == kChannelMagic) return static_cast<NativeChannel *>(userData)->context;
    return nullptr;
}

void unregisterChannel(const std::shared_ptr<NativeChannel> &channel) {
    if (!channel) return;
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gChannels.erase(reinterpret_cast<jlong>(channel.get()));
}

int connCreateNotify(xqc_connection_t *conn, const xqc_cid_t *cid, void *userData, void *) {
    if (auto *rawChannel = channelFromUserData(userData)) {
        auto channel = findChannel(rawChannel);
        if (!channel) return -1;
        channel->cid = *cid;
        xqc_conn_set_transport_user_data(conn, channel.get());
        xqc_conn_set_alp_user_data(conn, channel.get());
        return XQC_OK;
    }
    auto *ctx = contextFromUserData(userData);
    if (ctx == nullptr) return -1;
    auto channel = std::make_shared<NativeChannel>(ctx);
    channel->cid = *cid;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return -1;
        attached = true;
    }
    channel->receiver = env->NewGlobalRef(ctx->receiver);
    if (attached) gVm->DetachCurrentThread();
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->channels.push_back(channel);
    }
    registerChannel(channel);
    xqc_conn_set_transport_user_data(conn, channel.get());
    xqc_conn_set_alp_user_data(conn, channel.get());
    return XQC_OK;
}

int connCloseNotify(xqc_connection_t *, const xqc_cid_t *, void *userData, void *) {
    auto channel = findChannel(static_cast<NativeChannel *>(userData));
    if (!channel) return XQC_OK;
    {
        std::lock_guard<std::mutex> lock(channel->mutex);
        channel->closed = true;
    }
    notifyClosed(channel);
    return XQC_OK;
}

void handshakeFinished(xqc_connection_t *, void *userData, void *) {
    auto channel = findChannel(static_cast<NativeChannel *>(userData));
    if (channel && channel->context) {
        channel->handshakeComplete.store(true);
        channel->context->cv.notify_all();
    }
}

int streamCreateNotify(xqc_stream_t *stream, void *userData) {
    NativeChannel *raw = static_cast<NativeChannel *>(userData);
    if (raw == nullptr && stream != nullptr) {
        raw = static_cast<NativeChannel *>(piko_xqc_stream_proto_data(stream));
    }
    auto channel = findChannel(raw);
    if (!channel) return -1;
    channel->stream = stream;
    xqc_stream_set_user_data(stream, channel.get());
    return XQC_OK;
}

void readFrames(const std::shared_ptr<NativeChannel> &channel) {
    uint8_t buffer[kReadBufferBytes];
    while (channel->stream != nullptr) {
        uint8_t fin = 0;
        ssize_t read = xqc_stream_recv(channel->stream, buffer, sizeof(buffer), &fin);
        if (read == -XQC_EAGAIN) break;
        if (read <= 0) {
            if (read < 0) {
                std::lock_guard<std::mutex> lock(channel->mutex);
                channel->closed = true;
            }
            break;
        }
        channel->incoming.insert(channel->incoming.end(), buffer, buffer + read);
        while (channel->incoming.size() >= 4) {
            uint32_t length = 0;
            std::memcpy(&length, channel->incoming.data(), sizeof(length));
            length = ntohl(length);
            if (length == 0 || length > kMaxFrameBytes) {
                channel->closed = true;
                return;
            }
            if (channel->incoming.size() < 4 + length) break;
            notifyFrame(channel, channel->incoming.data() + 4, length);
            channel->incoming.erase(channel->incoming.begin(), channel->incoming.begin() + 4 + length);
        }
    }
}

int streamReadNotify(xqc_stream_t *, void *userData) {
    auto channel = findChannel(static_cast<NativeChannel *>(userData));
    if (channel) readFrames(channel);
    return XQC_OK;
}

void drainChannelSends(const std::shared_ptr<NativeChannel> &channel) {
    std::lock_guard<std::mutex> lock(channel->mutex);
    while (!channel->pending.empty() && channel->stream != nullptr && !channel->closed) {
        PendingFrame &frame = channel->pending.front();
        ssize_t sent = xqc_stream_send(
            channel->stream,
            frame.bytes.data() + frame.offset,
            frame.bytes.size() - frame.offset,
            0
        );
        if (sent == -XQC_EAGAIN) break;
        if (sent < 0) {
            channel->closed = true;
            break;
        }
        frame.offset += static_cast<size_t>(sent);
        if (frame.offset < frame.bytes.size()) break;
        channel->pending.erase(channel->pending.begin());
    }
}

int streamWriteNotify(xqc_stream_t *, void *userData) {
    auto channel = findChannel(static_cast<NativeChannel *>(userData));
    if (channel) drainChannelSends(channel);
    return XQC_OK;
}

int streamCloseNotify(xqc_stream_t *, void *userData) {
    auto channel = findChannel(static_cast<NativeChannel *>(userData));
    if (!channel) return XQC_OK;
    {
        std::lock_guard<std::mutex> lock(channel->mutex);
        channel->closed = true;
        channel->stream = nullptr;
    }
    notifyClosed(channel);
    return XQC_OK;
}

xqc_engine_t *createEngine(NativeContext *ctx, bool server, const std::string &keyPath, const std::string &certPath) {
    xqc_config_t config{};
    if (xqc_engine_get_default_config(&config, server ? XQC_ENGINE_SERVER : XQC_ENGINE_CLIENT) != XQC_OK) return nullptr;
    config.sendmmsg_on = 0;
    config.cfg_log_level = XQC_LOG_ERROR;
    xqc_engine_callback_t callbacks{};
    callbacks.set_event_timer = setTimer;
    callbacks.log_callbacks.xqc_log_write_err = noopLog;
    callbacks.log_callbacks.xqc_log_write_stat = noopLog;
    callbacks.log_callbacks.xqc_qlog_event_write = noopQlog;
    callbacks.monotonic_ts = nowMicros;
    xqc_transport_callbacks_t transport{};
    transport.server_accept = server ? serverAccept : nullptr;
    transport.write_socket = writeSocket;
    transport.write_socket_ex = writeSocketEx;
    xqc_engine_ssl_config_t ssl{};
    if (server) {
        ssl.private_key_file = const_cast<char *>(keyPath.c_str());
        ssl.cert_file = const_cast<char *>(certPath.c_str());
    }
    return xqc_engine_create(server ? XQC_ENGINE_SERVER : XQC_ENGINE_CLIENT, &config, &ssl, &callbacks, &transport, ctx);
}

bool registerAlpn(xqc_engine_t *engine, const std::string &alpn) {
    xqc_app_proto_callbacks_t callbacks{};
    callbacks.conn_cbs.conn_create_notify = connCreateNotify;
    callbacks.conn_cbs.conn_close_notify = connCloseNotify;
    callbacks.conn_cbs.conn_handshake_finished = handshakeFinished;
    callbacks.stream_cbs.stream_create_notify = streamCreateNotify;
    callbacks.stream_cbs.stream_read_notify = streamReadNotify;
    callbacks.stream_cbs.stream_write_notify = streamWriteNotify;
    callbacks.stream_cbs.stream_close_notify = streamCloseNotify;
    return xqc_engine_register_alpn(engine, alpn.c_str(), alpn.size(), &callbacks, nullptr) == XQC_OK;
}

int bindUdpSocket(const std::string &host, int port, sockaddr_storage *localAddr, socklen_t *localLen) {
    int fd = socket(AF_INET6, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in6 addr{};
    addr.sin6_family = AF_INET6;
    addr.sin6_port = htons(static_cast<uint16_t>(port));
    if (host.empty() || host == "::") {
        addr.sin6_addr = in6addr_any;
    } else if (inet_pton(AF_INET6, host.c_str(), &addr.sin6_addr) != 1) {
        close(fd);
        return -1;
    }
    if (bind(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    *localLen = sizeof(addr);
    if (getsockname(fd, reinterpret_cast<sockaddr *>(&addr), localLen) == 0) {
        std::memcpy(localAddr, &addr, *localLen);
    }
    return fd;
}

void loopContext(std::shared_ptr<NativeContext> ctx) {
    uint8_t packet[65535];
    while (!ctx->closed.load()) {
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(ctx->fd, &readSet);
        timeval tv{};
        {
            std::unique_lock<std::mutex> lock(ctx->mutex);
            tv.tv_sec = static_cast<long>(ctx->wakeAfter / 1000000);
            tv.tv_usec = static_cast<long>(ctx->wakeAfter % 1000000);
        }
        int ready = select(ctx->fd + 1, &readSet, nullptr, nullptr, &tv);
        if (ready > 0 && FD_ISSET(ctx->fd, &readSet)) {
            while (true) {
                sockaddr_storage peer{};
                socklen_t peerLen = sizeof(peer);
                ssize_t size = recvfrom(ctx->fd, packet, sizeof(packet), 0, reinterpret_cast<sockaddr *>(&peer), &peerLen);
                if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
                if (size <= 0) break;
                sockaddr_storage local{};
                socklen_t localLen = sizeof(local);
                getsockname(ctx->fd, reinterpret_cast<sockaddr *>(&local), &localLen);
                xqc_engine_packet_process(
                    ctx->engine,
                    packet,
                    static_cast<size_t>(size),
                    reinterpret_cast<sockaddr *>(&local),
                    localLen,
                    reinterpret_cast<sockaddr *>(&peer),
                    peerLen,
                    nowMicros(),
                    ctx.get()
                );
            }
            xqc_engine_finish_recv(ctx->engine);
        }
        std::vector<std::shared_ptr<NativeChannel>> channels;
        {
            std::lock_guard<std::mutex> lock(ctx->mutex);
            channels = ctx->channels;
        }
        for (const auto &channel : channels) drainChannelSends(channel);
        xqc_engine_main_logic(ctx->engine);
    }
}

void closeContext(const std::shared_ptr<NativeContext> &ctx) {
    if (!ctx || ctx->closed.exchange(true)) return;
    ctx->cv.notify_all();
    if (ctx->fd >= 0) {
        shutdown(ctx->fd, SHUT_RDWR);
        close(ctx->fd);
        ctx->fd = -1;
    }
    if (ctx->worker.joinable()) {
        if (ctx->worker.get_id() == std::this_thread::get_id()) {
            ctx->worker.detach();
        } else {
            ctx->worker.join();
        }
    }
    if (ctx->engine != nullptr) {
        xqc_engine_destroy(ctx->engine);
        ctx->engine = nullptr;
    }
    JNIEnv *env = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (ctx->receiver != nullptr) env->DeleteGlobalRef(ctx->receiver);
        for (auto &channel : ctx->channels) {
            if (channel->receiver != nullptr) env->DeleteGlobalRef(channel->receiver);
        }
    }
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gContexts.erase(reinterpret_cast<jlong>(ctx.get()));
    for (auto &channel : ctx->channels) gChannels.erase(reinterpret_cast<jlong>(channel.get()));
}

jlong nativeOpenServer(JNIEnv *env, jclass, jstring bindHost, jstring alpn, jstring certificateDirectory, jobject receiver) {
    const std::string bindHostText = jString(env, bindHost);
    const std::string alpnText = jString(env, alpn);
    const std::string certDirText = jString(env, certificateDirectory);
    std::string keyPath;
    std::string certPath;
    if (!ensureServerCertificate(certDirText, &keyPath, &certPath)) return 0;
    sockaddr_storage local{};
    socklen_t localLen = sizeof(local);
    auto ctx = std::make_shared<NativeContext>();
    ctx->server = true;
    ctx->fd = bindUdpSocket(bindHostText, 0, &local, &localLen);
    if (ctx->fd < 0) return 0;
    if (local.ss_family == AF_INET6) {
        ctx->port = ntohs(reinterpret_cast<sockaddr_in6 *>(&local)->sin6_port);
    }
    ctx->receiver = env->NewGlobalRef(receiver);
    ctx->engine = createEngine(ctx.get(), true, keyPath, certPath);
    if (ctx->engine == nullptr || !registerAlpn(ctx->engine, alpnText)) {
        closeContext(ctx);
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gContexts[reinterpret_cast<jlong>(ctx.get())] = ctx;
    }
    ctx->worker = std::thread(loopContext, ctx);
    return reinterpret_cast<jlong>(ctx.get());
}

jint nativeServerPort(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gContexts.find(handle);
    return it == gContexts.end() ? 0 : it->second->port;
}

void nativeCloseServer(JNIEnv *, jclass, jlong handle) {
    std::shared_ptr<NativeContext> ctx;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gContexts.find(handle);
        if (it != gContexts.end()) ctx = it->second;
    }
    closeContext(ctx);
}

jlong nativeOpenClient(JNIEnv *env, jclass, jstring host, jint port, jint timeoutMillis, jstring alpn, jobject receiver) {
    const std::string hostText = jString(env, host);
    const std::string alpnText = jString(env, alpn);
    sockaddr_in6 peer{};
    peer.sin6_family = AF_INET6;
    peer.sin6_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET6, hostText.c_str(), &peer.sin6_addr) != 1) return 0;
    sockaddr_storage local{};
    socklen_t localLen = sizeof(local);
    auto ctx = std::make_shared<NativeContext>();
    ctx->fd = bindUdpSocket("::", 0, &local, &localLen);
    if (ctx->fd < 0) return 0;
    ctx->engine = createEngine(ctx.get(), false, "", "");
    if (ctx->engine == nullptr || !registerAlpn(ctx->engine, alpnText)) {
        closeContext(ctx);
        return 0;
    }
    auto channel = std::make_shared<NativeChannel>(ctx.get());
    channel->clientOwnedContext = true;
    channel->receiver = env->NewGlobalRef(receiver);
    ctx->channels.push_back(channel);
    registerChannel(channel);
    xqc_conn_ssl_config_t ssl{};
    ssl.cert_verify_flag = 0;
    xqc_conn_settings_t connSettings{};
    const xqc_cid_t *cid = xqc_connect(
        ctx->engine,
        &connSettings,
        nullptr,
        0,
        hostText.c_str(),
        0,
        &ssl,
        reinterpret_cast<sockaddr *>(&peer),
        sizeof(peer),
        alpnText.c_str(),
        channel.get()
    );
    if (cid == nullptr) {
        unregisterChannel(channel);
        closeContext(ctx);
        return 0;
    }
    channel->cid = *cid;
    channel->stream = xqc_stream_create(ctx->engine, &channel->cid, nullptr, channel.get());
    if (channel->stream == nullptr) {
        unregisterChannel(channel);
        closeContext(ctx);
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gContexts[reinterpret_cast<jlong>(ctx.get())] = ctx;
    }
    ctx->worker = std::thread(loopContext, ctx);
    const int waitMillis = timeoutMillis > 0 ? timeoutMillis : 5000;
    {
        std::unique_lock<std::mutex> lock(ctx->mutex);
        const bool ready = ctx->cv.wait_for(
            lock,
            std::chrono::milliseconds(waitMillis),
            [&] {
                return channel->handshakeComplete.load() || channel->closed || ctx->closed.load();
            }
        );
        if (!ready || channel->closed || ctx->closed.load()) {
            closeContext(ctx);
            return 0;
        }
    }
    return reinterpret_cast<jlong>(channel.get());
}

jboolean nativeSendFrame(JNIEnv *env, jclass, jlong handle, jbyteArray frame) {
    std::shared_ptr<NativeChannel> channel;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gChannels.find(handle);
        if (it != gChannels.end()) channel = it->second;
    }
    if (!channel || frame == nullptr) return JNI_FALSE;
    const jsize size = env->GetArrayLength(frame);
    if (size <= 0 || static_cast<size_t>(size) > kMaxFrameBytes) return JNI_FALSE;
    PendingFrame pending;
    pending.bytes.resize(4 + static_cast<size_t>(size));
    uint32_t length = htonl(static_cast<uint32_t>(size));
    std::memcpy(pending.bytes.data(), &length, sizeof(length));
    env->GetByteArrayRegion(frame, 0, size, reinterpret_cast<jbyte *>(pending.bytes.data() + 4));
    {
        std::lock_guard<std::mutex> lock(channel->mutex);
        if (channel->closed) return JNI_FALSE;
        channel->pending.push_back(std::move(pending));
    }
    if (channel->context != nullptr) channel->context->cv.notify_all();
    return JNI_TRUE;
}

void nativeCloseChannel(JNIEnv *, jclass, jlong handle) {
    std::shared_ptr<NativeChannel> channel;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gChannels.find(handle);
        if (it != gChannels.end()) channel = it->second;
    }
    if (!channel) return;
    if (channel->clientOwnedContext && channel->context != nullptr) {
        std::shared_ptr<NativeContext> ctx;
        {
            std::lock_guard<std::mutex> lock(gRegistryMutex);
            auto it = gContexts.find(reinterpret_cast<jlong>(channel->context));
            if (it != gContexts.end()) ctx = it->second;
        }
        closeContext(ctx);
        return;
    }
    std::lock_guard<std::mutex> lock(channel->mutex);
    channel->closed = true;
}

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass receiverClass = env->FindClass("com/piko/app/transport/XQuicFrameReceiver");
    if (receiverClass == nullptr) return JNI_ERR;
    gOnFrame = env->GetMethodID(receiverClass, "onFrame", "(J[B)V");
    gOnClosed = env->GetMethodID(receiverClass, "onClosed", "(J)V");
    if (gOnFrame == nullptr || gOnClosed == nullptr) return JNI_ERR;
    jclass bridgeClass = env->FindClass("com/piko/app/transport/P2PXQuicNativeBridge");
    if (bridgeClass == nullptr) return JNI_ERR;
    JNINativeMethod methods[] = {
        {"openServer", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/piko/app/transport/XQuicFrameReceiver;)J", reinterpret_cast<void *>(nativeOpenServer)},
        {"serverPort", "(J)I", reinterpret_cast<void *>(nativeServerPort)},
        {"closeServerNative", "(J)V", reinterpret_cast<void *>(nativeCloseServer)},
        {"openClient", "(Ljava/lang/String;IILjava/lang/String;Lcom/piko/app/transport/XQuicFrameReceiver;)J", reinterpret_cast<void *>(nativeOpenClient)},
        {"sendFrame", "(J[B)Z", reinterpret_cast<void *>(nativeSendFrame)},
        {"closeChannelNative", "(J)V", reinterpret_cast<void *>(nativeCloseChannel)},
    };
    if (env->RegisterNatives(bridgeClass, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
