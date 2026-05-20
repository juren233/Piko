#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
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
constexpr uint32_t kStunMagicCookie = 0x2112A442;
constexpr int kStunProbeTimeoutMillis = 700;

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
    std::string mappedHost;
    int mappedPort = 0;
    std::string stunProbeResults;
    xqc_engine_t *engine = nullptr;
    jobject receiver = nullptr;
    std::mutex mutex;
    std::condition_variable cv;
    std::thread worker;
    std::atomic<bool> closed{false};
    std::atomic<bool> cleaned{false};
    xqc_usec_t wakeAfter = 10000;
    std::vector<std::shared_ptr<NativeChannel>> channels;
};

std::mutex gRegistryMutex;
std::unordered_map<jlong, std::shared_ptr<NativeContext>> gContexts;
std::unordered_map<jlong, std::shared_ptr<NativeChannel>> gChannels;

NativeContext *contextFromUserData(void *userData);
void cleanupContext(const std::shared_ptr<NativeContext> &ctx);

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

int fdSnapshot(NativeContext *ctx) {
    if (ctx == nullptr) return -1;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    return ctx->fd;
}

int detachFd(const std::shared_ptr<NativeContext> &ctx) {
    if (!ctx) return -1;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    const int fd = ctx->fd;
    ctx->fd = -1;
    return fd;
}

ssize_t writeSocketEx(uint64_t, const unsigned char *buf, size_t size, const struct sockaddr *peerAddr,
                      socklen_t peerAddrLen, void *connUserData) {
    NativeContext *ctx = contextFromUserData(connUserData);
    const int fd = fdSnapshot(ctx);
    if (fd < 0) return XQC_SOCKET_ERROR;
    ssize_t sent;
    do {
        sent = sendto(fd, buf, size, 0, peerAddr, peerAddrLen);
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

void connUpdateCidNotify(xqc_connection_t *, const xqc_cid_t *, const xqc_cid_t *, void *) {}

void saveToken(const unsigned char *, uint32_t, void *) {}

void saveString(const char *, size_t, void *) {}

int verifyCertificate(const unsigned char *[], const size_t [], size_t, void *) {
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
    transport.conn_update_cid_notify = connUpdateCidNotify;
    transport.save_token = saveToken;
    transport.save_session_cb = saveString;
    transport.save_tp_cb = saveString;
    transport.cert_verify_cb = verifyCertificate;
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

uint16_t readUint16(const uint8_t *data) {
    uint16_t value = 0;
    std::memcpy(&value, data, sizeof(value));
    return ntohs(value);
}

void writeUint16(uint8_t *data, uint16_t value) {
    const uint16_t network = htons(value);
    std::memcpy(data, &network, sizeof(network));
}

bool copyAddress(const sockaddr *source, socklen_t sourceLen, bool ipv4AsMapped, sockaddr_storage *target, socklen_t *targetLen) {
    if (source == nullptr || target == nullptr || targetLen == nullptr) return false;
    std::memset(target, 0, sizeof(*target));
    if (source->sa_family == AF_INET) {
        auto ipv4 = *reinterpret_cast<const sockaddr_in *>(source);
        if (!ipv4AsMapped) {
            std::memcpy(target, &ipv4, sizeof(ipv4));
            *targetLen = sizeof(ipv4);
            return true;
        }
        sockaddr_in6 mapped{};
        mapped.sin6_family = AF_INET6;
        mapped.sin6_port = ipv4.sin_port;
        uint8_t *bytes = reinterpret_cast<uint8_t *>(&mapped.sin6_addr);
        bytes[10] = 0xff;
        bytes[11] = 0xff;
        std::memcpy(bytes + 12, &ipv4.sin_addr, sizeof(ipv4.sin_addr));
        std::memcpy(target, &mapped, sizeof(mapped));
        *targetLen = sizeof(mapped);
        return true;
    }
    if (source->sa_family == AF_INET6 && sourceLen >= static_cast<socklen_t>(sizeof(sockaddr_in6))) {
        std::memcpy(target, source, sizeof(sockaddr_in6));
        *targetLen = sizeof(sockaddr_in6);
        return true;
    }
    return false;
}

bool makeSocketAddress(const std::string &host, int port, bool preferIpv4, bool ipv4AsMapped, sockaddr_storage *target, socklen_t *targetLen) {
    if (host.empty() || port <= 0 || port > 65535 || target == nullptr || targetLen == nullptr) return false;
    sockaddr_in6 ipv6{};
    ipv6.sin6_family = AF_INET6;
    ipv6.sin6_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET6, host.c_str(), &ipv6.sin6_addr) == 1) {
        std::memset(target, 0, sizeof(*target));
        std::memcpy(target, &ipv6, sizeof(ipv6));
        *targetLen = sizeof(ipv6);
        return true;
    }
    sockaddr_in ipv4{};
    ipv4.sin_family = AF_INET;
    ipv4.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host.c_str(), &ipv4.sin_addr) == 1) {
        return copyAddress(reinterpret_cast<sockaddr *>(&ipv4), sizeof(ipv4), ipv4AsMapped, target, targetLen);
    }
    addrinfo hints{};
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_family = AF_UNSPEC;
    addrinfo *result = nullptr;
    const std::string portText = std::to_string(port);
    if (getaddrinfo(host.c_str(), portText.c_str(), &hints, &result) != 0 || result == nullptr) return false;
    auto guard = std::unique_ptr<addrinfo, decltype(&freeaddrinfo)>(result, freeaddrinfo);
    for (int pass = 0; pass < 2; pass++) {
        const int wanted = preferIpv4 == (pass == 0) ? AF_INET : AF_INET6;
        for (addrinfo *item = result; item != nullptr; item = item->ai_next) {
            if (item->ai_family != wanted) continue;
            if (copyAddress(item->ai_addr, static_cast<socklen_t>(item->ai_addrlen), ipv4AsMapped, target, targetLen)) return true;
        }
    }
    return false;
}

bool mappedEndpointFromStunAttribute(const uint8_t *value, size_t length, bool xorMapped, const uint8_t *transactionId, std::string *host, int *port) {
    if (value == nullptr || host == nullptr || port == nullptr || length < 4) return false;
    const uint8_t family = value[1];
    const uint16_t encodedPort = readUint16(value + 2);
    const uint16_t decodedPort = xorMapped ? static_cast<uint16_t>(encodedPort ^ (kStunMagicCookie >> 16)) : encodedPort;
    char text[INET6_ADDRSTRLEN]{};
    if (family == 0x01 && length >= 8) {
        uint32_t address = 0;
        std::memcpy(&address, value + 4, sizeof(address));
        if (xorMapped) {
            const uint32_t decoded = ntohl(address) ^ kStunMagicCookie;
            address = htonl(decoded);
        }
        if (inet_ntop(AF_INET, &address, text, sizeof(text)) == nullptr) return false;
        *host = text;
        *port = decodedPort;
        return true;
    }
    if (family == 0x02 && length >= 20) {
        uint8_t decoded[16]{};
        std::memcpy(decoded, value + 4, sizeof(decoded));
        if (xorMapped) {
            const uint8_t cookie[] = {0x21, 0x12, 0xA4, 0x42};
            for (int index = 0; index < 4; index++) decoded[index] ^= cookie[index];
            for (int index = 0; index < 12; index++) decoded[index + 4] ^= transactionId[index];
        }
        if (inet_ntop(AF_INET6, decoded, text, sizeof(text)) == nullptr) return false;
        *host = text;
        *port = decodedPort;
        return true;
    }
    return false;
}

bool parseStunMappedEndpoint(const uint8_t *packet, size_t size, const uint8_t *transactionId, std::string *host, int *port) {
    if (packet == nullptr || transactionId == nullptr || size < 20) return false;
    if (readUint16(packet) != 0x0101) return false;
    const uint16_t messageLength = readUint16(packet + 2);
    uint32_t cookie = 0;
    std::memcpy(&cookie, packet + 4, sizeof(cookie));
    if (ntohl(cookie) != kStunMagicCookie) return false;
    if (std::memcmp(packet + 8, transactionId, 12) != 0) return false;
    const size_t end = std::min(size, static_cast<size_t>(20 + messageLength));
    size_t offset = 20;
    while (offset + 4 <= end) {
        const uint16_t type = readUint16(packet + offset);
        const uint16_t length = readUint16(packet + offset + 2);
        const size_t valueOffset = offset + 4;
        if (valueOffset + length > end) break;
        if ((type == 0x0020 || type == 0x0001) &&
            mappedEndpointFromStunAttribute(packet + valueOffset, length, type == 0x0020, transactionId, host, port)) {
            return true;
        }
        offset = valueOffset + ((length + 3) & ~static_cast<size_t>(3));
    }
    return false;
}

void fillTransactionId(uint8_t *transactionId, int fd) {
    uint64_t seed = static_cast<uint64_t>(nowMicros()) ^ (static_cast<uint64_t>(fd) << 32);
    for (int index = 0; index < 12; index++) {
        seed = seed * 1103515245ULL + 12345ULL + static_cast<uint64_t>(index);
        transactionId[index] = static_cast<uint8_t>((seed >> 24) & 0xff);
    }
}

bool probeStunMappedEndpoint(int fd, const std::string &stunHost, int stunPort, std::string *mappedHost, int *mappedPort, std::string *error) {
    if (error != nullptr) error->clear();
    if (fd < 0 || stunHost.empty() || stunPort <= 0 || mappedHost == nullptr || mappedPort == nullptr) {
        if (error != nullptr) *error = "parse_error";
        return false;
    }
    sockaddr_storage stunAddress{};
    socklen_t stunAddressLen = sizeof(stunAddress);
    if (!makeSocketAddress(stunHost, stunPort, true, true, &stunAddress, &stunAddressLen)) {
        if (error != nullptr) *error = "dns_error";
        return false;
    }
    uint8_t transactionId[12]{};
    fillTransactionId(transactionId, fd);
    uint8_t request[20]{};
    writeUint16(request, 0x0001);
    writeUint16(request + 2, 0);
    const uint32_t cookie = htonl(kStunMagicCookie);
    std::memcpy(request + 4, &cookie, sizeof(cookie));
    std::memcpy(request + 8, transactionId, sizeof(transactionId));
    if (sendto(fd, request, sizeof(request), 0, reinterpret_cast<sockaddr *>(&stunAddress), stunAddressLen) < 0) {
        if (error != nullptr) *error = "send_error";
        return false;
    }
    uint8_t response[1024]{};
    for (int attempt = 0; attempt < 2; attempt++) {
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(fd, &readSet);
        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = kStunProbeTimeoutMillis * 1000;
        const int ready = select(fd + 1, &readSet, nullptr, nullptr, &tv);
        if (ready <= 0 || !FD_ISSET(fd, &readSet)) continue;
        sockaddr_storage peer{};
        socklen_t peerLen = sizeof(peer);
        const ssize_t size = recvfrom(fd, response, sizeof(response), 0, reinterpret_cast<sockaddr *>(&peer), &peerLen);
        if (size <= 0) continue;
        if (parseStunMappedEndpoint(response, static_cast<size_t>(size), transactionId, mappedHost, mappedPort)) return true;
    }
    if (error != nullptr) *error = "timeout";
    return false;
}

struct StunProbeRecord {
    std::string serverUrl;
    bool success = false;
    std::string mappedHost;
    int mappedPort = 0;
    std::string error;
    long long elapsedMs = 0;
};

static bool parseStunUrl(const std::string &url, std::string *outHost, int *outPort) {
    const std::string prefix = "stun:";
    if (url.size() < prefix.size()) return false;
    for (size_t i = 0; i < prefix.size(); ++i) {
        if (tolower(static_cast<unsigned char>(url[i])) != prefix[i]) return false;
    }
    std::string rest = url.substr(prefix.size());
    auto qPos = rest.find('?');
    if (qPos != std::string::npos) rest = rest.substr(0, qPos);
    auto slPos = rest.find('/');
    if (slPos != std::string::npos) rest = rest.substr(0, slPos);
    if (rest.empty()) return false;
    if (rest[0] == '[') {
        auto end = rest.find(']');
        if (end == std::string::npos) return false;
        *outHost = rest.substr(1, end - 1);
        const std::string portPart = rest.substr(end + 1);
        if (!portPart.empty() && portPart[0] == ':') {
            const int p = std::atoi(portPart.c_str() + 1);
            *outPort = (p > 0 && p <= 65535) ? p : 3478;
        } else {
            *outPort = 3478;
        }
    } else {
        const auto colon = rest.rfind(':');
        if (colon != std::string::npos) {
            *outHost = rest.substr(0, colon);
            const int p = std::atoi(rest.c_str() + colon + 1);
            *outPort = (p > 0 && p <= 65535) ? p : 3478;
        } else {
            *outHost = rest;
            *outPort = 3478;
        }
    }
    return !outHost->empty() && *outPort > 0 && *outPort <= 65535;
}

static std::vector<StunProbeRecord> probeMultipleStun(int fd, const std::string &stunTargets) {
    std::vector<StunProbeRecord> records;
    size_t start = 0;
    const size_t len = stunTargets.size();
    while (start <= len) {
        size_t end = stunTargets.find('\n', start);
        if (end == std::string::npos) end = len;
        std::string line = stunTargets.substr(start, end - start);
        start = end + 1;
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        while (!line.empty() && line.front() == ' ') line = line.substr(1);
        if (line.empty()) continue;
        StunProbeRecord rec;
        rec.serverUrl = line;
        std::string host;
        int port = 0;
        if (!parseStunUrl(line, &host, &port)) {
            rec.error = "parse_error";
            records.push_back(rec);
            continue;
        }
        const long long probeStart = static_cast<long long>(nowMicros());
        std::string mappedHost;
        int mappedPort = 0;
        std::string error;
        rec.success = probeStunMappedEndpoint(fd, host, port, &mappedHost, &mappedPort, &error);
        rec.elapsedMs = (static_cast<long long>(nowMicros()) - probeStart) / 1000;
        if (rec.success) {
            rec.mappedHost = mappedHost;
            rec.mappedPort = mappedPort;
        } else {
            rec.error = error.empty() ? "timeout" : error;
        }
        records.push_back(rec);
    }
    return records;
}

static const StunProbeRecord *selectPreferredStunRecord(const std::vector<StunProbeRecord> &records) {
    const StunProbeRecord *best = nullptr;
    int bestCount = 0;
    for (const auto &candidate : records) {
        if (!candidate.success || candidate.mappedHost.empty() || candidate.mappedPort <= 0) continue;
        const int count = static_cast<int>(std::count_if(records.begin(), records.end(), [&](const StunProbeRecord &record) {
            return record.success &&
                   record.mappedHost == candidate.mappedHost &&
                   record.mappedPort == candidate.mappedPort;
        }));
        if (best == nullptr || count > bestCount) {
            best = &candidate;
            bestCount = count;
        }
    }
    return best;
}

static std::string serializeStunProbeResults(const std::vector<StunProbeRecord> &records) {
    std::string result;
    for (size_t i = 0; i < records.size(); ++i) {
        if (i > 0) result += ";";
        const auto &r = records[i];
        result += r.serverUrl + "|" + (r.success ? "true" : "false") + "|" +
                  r.mappedHost + "|" + std::to_string(r.mappedPort) + "|" +
                  r.error + "|" + std::to_string(r.elapsedMs);
    }
    return result;
}

int bindUdpSocket(const std::string &host, int port, sockaddr_storage *localAddr, socklen_t *localLen) {
    sockaddr_storage bindAddress{};
    socklen_t bindAddressLen = sizeof(bindAddress);
    if (host.empty() || host == "::") {
        sockaddr_in6 any{};
        any.sin6_family = AF_INET6;
        any.sin6_port = htons(static_cast<uint16_t>(port));
        any.sin6_addr = in6addr_any;
        std::memcpy(&bindAddress, &any, sizeof(any));
        bindAddressLen = sizeof(any);
    } else if (host == "0.0.0.0") {
        sockaddr_in any{};
        any.sin_family = AF_INET;
        any.sin_port = htons(static_cast<uint16_t>(port));
        any.sin_addr.s_addr = htonl(INADDR_ANY);
        std::memcpy(&bindAddress, &any, sizeof(any));
        bindAddressLen = sizeof(any);
    } else if (!makeSocketAddress(host, port, false, false, &bindAddress, &bindAddressLen)) {
        return -1;
    }
    int fd = socket(bindAddress.ss_family, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    if (bindAddress.ss_family == AF_INET6) {
        int no = 0;
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &no, sizeof(no));
    }
    if (bind(fd, reinterpret_cast<sockaddr *>(&bindAddress), bindAddressLen) != 0) {
        close(fd);
        return -1;
    }
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    *localLen = sizeof(*localAddr);
    if (getsockname(fd, reinterpret_cast<sockaddr *>(localAddr), localLen) != 0) {
        *localLen = bindAddressLen;
        std::memcpy(localAddr, &bindAddress, bindAddressLen);
    }
    return fd;
}

void loopContext(std::shared_ptr<NativeContext> ctx) {
    uint8_t packet[65535];
    while (!ctx->closed.load()) {
        const int fd = fdSnapshot(ctx.get());
        if (fd < 0 || ctx->engine == nullptr) break;
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(fd, &readSet);
        timeval tv{};
        {
            std::unique_lock<std::mutex> lock(ctx->mutex);
            tv.tv_sec = static_cast<long>(ctx->wakeAfter / 1000000);
            tv.tv_usec = static_cast<long>(ctx->wakeAfter % 1000000);
        }
        int ready = select(fd + 1, &readSet, nullptr, nullptr, &tv);
        if (ctx->closed.load()) break;
        if (ready > 0 && FD_ISSET(fd, &readSet)) {
            while (!ctx->closed.load()) {
                sockaddr_storage peer{};
                socklen_t peerLen = sizeof(peer);
                ssize_t size = recvfrom(fd, packet, sizeof(packet), 0, reinterpret_cast<sockaddr *>(&peer), &peerLen);
                if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
                if (size <= 0) break;
                sockaddr_storage local{};
                socklen_t localLen = sizeof(local);
                getsockname(fd, reinterpret_cast<sockaddr *>(&local), &localLen);
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
            if (!ctx->closed.load()) xqc_engine_finish_recv(ctx->engine);
        }
        if (ctx->closed.load()) break;
        std::vector<std::shared_ptr<NativeChannel>> channels;
        {
            std::lock_guard<std::mutex> lock(ctx->mutex);
            channels = ctx->channels;
        }
        for (const auto &channel : channels) drainChannelSends(channel);
        xqc_engine_main_logic(ctx->engine);
    }
    ctx->closed.store(true);
    cleanupContext(ctx);
}

void cleanupContext(const std::shared_ptr<NativeContext> &ctx) {
    if (!ctx || ctx->cleaned.exchange(true)) return;
    if (ctx->engine != nullptr) {
        xqc_engine_destroy(ctx->engine);
        ctx->engine = nullptr;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    if (env != nullptr) {
        if (ctx->receiver != nullptr) env->DeleteGlobalRef(ctx->receiver);
        ctx->receiver = nullptr;
        for (auto &channel : ctx->channels) {
            if (channel->receiver != nullptr) env->DeleteGlobalRef(channel->receiver);
            channel->receiver = nullptr;
        }
    }
    if (attached) gVm->DetachCurrentThread();
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gContexts.erase(reinterpret_cast<jlong>(ctx.get()));
    for (auto &channel : ctx->channels) gChannels.erase(reinterpret_cast<jlong>(channel.get()));
}

void closeContext(const std::shared_ptr<NativeContext> &ctx) {
    if (!ctx || ctx->closed.exchange(true)) return;
    ctx->cv.notify_all();
    const int fd = detachFd(ctx);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
    if (ctx->worker.joinable()) {
        if (ctx->worker.get_id() == std::this_thread::get_id()) {
            ctx->worker.detach();
            return;
        } else {
            ctx->worker.join();
        }
    }
    cleanupContext(ctx);
}

jlong nativeOpenServer(JNIEnv *env, jclass, jstring bindHost, jstring alpn, jstring certificateDirectory, jstring stunTargets, jobject receiver) {
    const std::string bindHostText = jString(env, bindHost);
    const std::string alpnText = jString(env, alpn);
    const std::string certDirText = jString(env, certificateDirectory);
    const std::string stunTargetsText = jString(env, stunTargets);
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
    } else if (local.ss_family == AF_INET) {
        ctx->port = ntohs(reinterpret_cast<sockaddr_in *>(&local)->sin_port);
    }
    if (!stunTargetsText.empty()) {
        const auto probeRecords = probeMultipleStun(ctx->fd, stunTargetsText);
        ctx->stunProbeResults = serializeStunProbeResults(probeRecords);
        if (const StunProbeRecord *selected = selectPreferredStunRecord(probeRecords)) {
            ctx->mappedHost = selected->mappedHost;
            ctx->mappedPort = selected->mappedPort;
        }
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

jstring nativeMappedEndpoint(JNIEnv *env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gContexts.find(handle);
    if (it == gContexts.end() || it->second->mappedHost.empty() || it->second->mappedPort <= 0) return nullptr;
    const std::string value = it->second->mappedHost + "|" + std::to_string(it->second->mappedPort);
    return env->NewStringUTF(value.c_str());
}

jstring nativeStunProbeResults(JNIEnv *env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gContexts.find(handle);
    if (it == gContexts.end() || it->second->stunProbeResults.empty()) return nullptr;
    return env->NewStringUTF(it->second->stunProbeResults.c_str());
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
    sockaddr_storage peer{};
    socklen_t peerLen = sizeof(peer);
    if (!makeSocketAddress(hostText, static_cast<int>(port), false, false, &peer, &peerLen)) return 0;
    sockaddr_storage local{};
    socklen_t localLen = sizeof(local);
    auto ctx = std::make_shared<NativeContext>();
    ctx->fd = bindUdpSocket(peer.ss_family == AF_INET ? "0.0.0.0" : "::", 0, &local, &localLen);
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
        peerLen,
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
        {"openServer", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/piko/app/transport/XQuicFrameReceiver;)J", reinterpret_cast<void *>(nativeOpenServer)},
        {"serverPort", "(J)I", reinterpret_cast<void *>(nativeServerPort)},
        {"mappedEndpoint", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeMappedEndpoint)},
        {"stunProbeResults", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeStunProbeResults)},
        {"closeServerNative", "(J)V", reinterpret_cast<void *>(nativeCloseServer)},
        {"openClient", "(Ljava/lang/String;IILjava/lang/String;Lcom/piko/app/transport/XQuicFrameReceiver;)J", reinterpret_cast<void *>(nativeOpenClient)},
        {"sendFrame", "(J[B)Z", reinterpret_cast<void *>(nativeSendFrame)},
        {"closeChannelNative", "(J)V", reinterpret_cast<void *>(nativeCloseChannel)},
    };
    if (env->RegisterNatives(bridgeClass, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
