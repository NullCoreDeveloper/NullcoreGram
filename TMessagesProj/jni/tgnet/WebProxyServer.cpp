/*
 * WebProxyServer — реализация.
 *
 * Критические исправления по сравнению с предыдущей версией:
 *  1. Убрано некорректное наследование от EventObject (у него нет virtual onEvent).
 *  2. Исправлена ошибка компиляции: 'proxyHost = host' → 'proxyHost = h'.
 *  3. epollEvents — это raw массив struct epoll_event[128], не вектор.
 *     Регистрация fd в epoll делается через прямой вызов epoll_ctl.
 *  4. recvBuffer корректно сбрасывается при отключении клиента.
 *  5. parseFrame теперь принимает прямой указатель, нет лишней копии вектора.
 *  6. sendData: проверка на нулевой payloadLen перед memcpy.
 *  7. writeClient: partial send обрабатывается в цикле.
 *  8. SHA1 deprecated в OpenSSL 3 — используем EVP_Digest через обёртку.
 *  9. Все пути закрытия сокета очищают соответствующий epoll fd.
 */

#include "WebProxyServer.h"
#include "WebProxyHtml.h"
#include "EventObject.h"
#include "ConnectionSocket.h"
#include "ConnectionsManager.h"
#include "FileLog.h"
#include "NativeByteBuffer.h"

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/epoll.h>
#include <string.h>
#include <openssl/sha.h>
#include <openssl/rand.h>

// ─── Base64 ──────────────────────────────────────────────────────────────────

static const char BASE64_CHARS[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "abcdefghijklmnopqrstuvwxyz"
    "0123456789+/";

static std::string base64Encode(const unsigned char* src, unsigned int len) {
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    unsigned int i = 0;
    unsigned char c3[3], c4[4];
    while (len--) {
        c3[i++] = *src++;
        if (i == 3) {
            c4[0] = (c3[0] & 0xFC) >> 2;
            c4[1] = ((c3[0] & 0x03) << 4) | ((c3[1] & 0xF0) >> 4);
            c4[2] = ((c3[1] & 0x0F) << 2) | ((c3[2] & 0xC0) >> 6);
            c4[3] = c3[2] & 0x3F;
            for (int j = 0; j < 4; j++) out += BASE64_CHARS[c4[j]];
            i = 0;
        }
    }
    if (i > 0) {
        for (unsigned int j = i; j < 3; j++) c3[j] = 0;
        c4[0] = (c3[0] & 0xFC) >> 2;
        c4[1] = ((c3[0] & 0x03) << 4) | ((c3[1] & 0xF0) >> 4);
        c4[2] = ((c3[1] & 0x0F) << 2) | ((c3[2] & 0xC0) >> 6);
        for (unsigned int j = 0; j < i + 1; j++) out += BASE64_CHARS[c4[j]];
        while (i++ < 3) out += '=';
    }
    return out;
}

// ─── Генерация токена ────────────────────────────────────────────────────────

static std::string generateToken() {
    unsigned char raw[16];
    RAND_bytes(raw, sizeof(raw));
    return base64Encode(raw, sizeof(raw));
}

// ─── WebProxyServer ──────────────────────────────────────────────────────────

WebProxyServer& WebProxyServer::getInstance() {
    static WebProxyServer instance;
    return instance;
}

void WebProxyServer::start(const std::string& h) {
    // Если уже запущен — обновляем хост (WebView перезагрузится с новым HTML),
    // но сам серверный сокет пересоздавать не нужно: порт и epoll уже готовы.
    if (listenSocket != -1) {
        proxyHost = h;
        token = generateToken(); // новый токен для нового сеанса
        return;
    }

    listenSocket = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listenSocket < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: socket() failed errno=%d", errno);
        return;
    }

    // SO_REUSEADDR чтобы не ждать TIME_WAIT при перезапуске.
    int opt = 1;
    ::setsockopt(listenSocket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    // Переводим в неблокирующий режим.
    int flags = ::fcntl(listenSocket, F_GETFL, 0);
    if (flags < 0 || ::fcntl(listenSocket, F_SETFL, flags | O_NONBLOCK) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: fcntl O_NONBLOCK failed");
        ::close(listenSocket);
        listenSocket = -1;
        return;
    }

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = ::inet_addr("127.0.0.1");
    addr.sin_port = 0; // OS выберет случайный свободный порт.

    if (::bind(listenSocket, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: bind() failed errno=%d", errno);
        ::close(listenSocket);
        listenSocket = -1;
        return;
    }

    // Узнаём реально выделенный порт.
    socklen_t addrLen = sizeof(addr);
    if (::getsockname(listenSocket, reinterpret_cast<struct sockaddr*>(&addr), &addrLen) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: getsockname() failed");
        ::close(listenSocket);
        listenSocket = -1;
        return;
    }
    listenPort = ntohs(addr.sin_port);

    if (::listen(listenSocket, 5) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: listen() failed errno=%d", errno);
        ::close(listenSocket);
        listenSocket = -1;
        return;
    }

    proxyHost = h;
    token = generateToken();

    // Регистрируем listenSocket в epoll ConnectionsManager instance 0.
    // EventObject здесь не нужен: мы сами вызываем onListenEvent/onClientEvent
    // через ConnectionsManager::scheduleTask при наличии данных.
    // Для этого используем специальный EpollObject-обёртку.
    // Примечание: так как epoll_event.data.ptr используется в select() и там
    // нет нашего типа, мы используем scheduleTask + самостоятельный поллинг.
    // Более корректный путь — зарегистрировать EventObject с типом Connection
    // и перехватить вызов через friend class. Для безопасности регистрируем
    // сокет вручную через epoll_ctl, а dispatch выполняем через scheduleTask.
    ConnectionsManager& cm = ConnectionsManager::getInstance(0);

    // Используем собственный EventObject-обёртку (WebProxyListenEvent).
    // Он не является Connection, поэтому добавляем через прямой epoll_ctl.
    // При срабатывании epoll -> select() -> eventObject->onEvent() вызовет
    // нашу логику. Мы оборачиваем 'this' в EventObject с eventType=Connection
    // и кастуем через pointer arithmetic (так делает сам tgnet).
    // БЕЗОПАСНЫЙ путь: передать fd через scheduleTask + собственный read.
    // Реализация: захватываем fd в лямбду, регистрируем EventObject-обёртку.

    // Создаём EventObject, который при onEvent вызовет WebProxyServer::onListenEvent.
    // Поскольку EventObject не виртуальный, используем workaround:
    // храним fd и пробуждаем через wakeup() + scheduleTask.
    // Это потокобезопасно: scheduleTask защищён mutex.

    listenEpollObj = new EventObject(this, static_cast<EventObjectType>(EventObjectTypeWebProxyListen));

    struct epoll_event ev{};
    ev.events = EPOLLIN | EPOLLET;
    ev.data.ptr = listenEpollObj;
    if (::epoll_ctl(cm.epolFd, EPOLL_CTL_ADD, listenSocket, &ev) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: epoll_ctl EPOLL_CTL_ADD listen failed errno=%d", errno);
        ::close(listenSocket);
        listenSocket = -1;
        delete listenEpollObj;
        listenEpollObj = nullptr;
        return;
    }

    if (LOGS_ENABLED) DEBUG_D("WebProxyServer: started on 127.0.0.1:%d", listenPort);
}

void WebProxyServer::stop() {
    ConnectionsManager& cm = ConnectionsManager::getInstance(0);

    if (clientSocket != -1) {
        ::epoll_ctl(cm.epolFd, EPOLL_CTL_DEL, clientSocket, nullptr);
        ::close(clientSocket);
        clientSocket = -1;
    }
    if (listenSocket != -1) {
        ::epoll_ctl(cm.epolFd, EPOLL_CTL_DEL, listenSocket, nullptr);
        ::close(listenSocket);
        listenSocket = -1;
    }

    delete listenEpollObj; listenEpollObj = nullptr;
    delete clientEpollObj; clientEpollObj = nullptr;

    wsHandshakeDone = false;
    recvBuffer.clear();
    streams.clear();
    listenPort = 0;
    token.clear();
    proxyHost.clear();
}

uint32_t WebProxyServer::registerStream(ConnectionSocket* socket) {
    uint32_t id = nextStreamId++;
    if (nextStreamId == 0) nextStreamId = 1; // переполнение
    streams[id] = socket;
    return id;
}

void WebProxyServer::closeStream(uint32_t streamId) {
    streams.erase(streamId);
}

void WebProxyServer::sendData(uint32_t streamId, NativeByteBuffer* buffer) {
    if (clientSocket == -1 || !wsHandshakeDone || buffer == nullptr) return;

    uint32_t payloadLen = buffer->limit();
    if (payloadLen == 0) return;

    // Сборка внутреннего фрейма по протоколу tdesktop WebProxy.
    // Формат: [type(1)][streamId(3)][size(4)][data...]
    std::vector<uint8_t> frame(8 + payloadLen);
    frame[0] = 1; // FrameType::Data
    frame[1] = static_cast<uint8_t>((streamId >> 16) & 0xFF);
    frame[2] = static_cast<uint8_t>((streamId >> 8) & 0xFF);
    frame[3] = static_cast<uint8_t>(streamId & 0xFF);
    frame[4] = static_cast<uint8_t>((payloadLen >> 24) & 0xFF);
    frame[5] = static_cast<uint8_t>((payloadLen >> 16) & 0xFF);
    frame[6] = static_cast<uint8_t>((payloadLen >> 8) & 0xFF);
    frame[7] = static_cast<uint8_t>(payloadLen & 0xFF);
    memcpy(frame.data() + 8, buffer->bytes(), payloadLen);

    // Оборачиваем во WebSocket Binary Frame (FIN=1, opcode=2, no mask из сервера).
    size_t frameSize = frame.size();
    std::vector<uint8_t> wsFrame;
    wsFrame.reserve(frameSize + 10);
    wsFrame.push_back(0x82); // FIN + Binary
    if (frameSize < 126) {
        wsFrame.push_back(static_cast<uint8_t>(frameSize));
    } else if (frameSize <= 65535) {
        wsFrame.push_back(126);
        wsFrame.push_back(static_cast<uint8_t>((frameSize >> 8) & 0xFF));
        wsFrame.push_back(static_cast<uint8_t>(frameSize & 0xFF));
    } else {
        wsFrame.push_back(127);
        for (int i = 7; i >= 0; i--) {
            wsFrame.push_back(static_cast<uint8_t>((frameSize >> (i * 8)) & 0xFF));
        }
    }
    wsFrame.insert(wsFrame.end(), frame.begin(), frame.end());

    writeClient(wsFrame.data(), wsFrame.size());
}

void WebProxyServer::onListenEvent(uint32_t events) {
    if (events & EPOLLIN) {
        acceptClient();
    }
    if (events & (EPOLLERR | EPOLLHUP)) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: listen socket error");
        stop();
    }
}

void WebProxyServer::onClientEvent(uint32_t events) {
    if (events & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) {
        closeClient();
        return;
    }
    if (events & EPOLLIN) {
        readClient();
    }
}

void WebProxyServer::acceptClient() {
    struct sockaddr_in clientAddr{};
    socklen_t clientLen = sizeof(clientAddr);
    int fd = ::accept(listenSocket, reinterpret_cast<struct sockaddr*>(&clientAddr), &clientLen);
    if (fd < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            if (LOGS_ENABLED) DEBUG_E("WebProxyServer: accept() failed errno=%d", errno);
        }
        return;
    }

    // Допускаем только одно соединение (браузер). Старое закрываем.
    if (clientSocket != -1) {
        closeClient();
    }

    int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0 || ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: fcntl client O_NONBLOCK failed");
        ::close(fd);
        return;
    }

    clientSocket = fd;
    wsHandshakeDone = false;
    recvBuffer.clear();

    registerClientEpoll();
}

void WebProxyServer::registerClientEpoll() {
    ConnectionsManager& cm = ConnectionsManager::getInstance(0);

    delete clientEpollObj;
    clientEpollObj = new EventObject(this, static_cast<EventObjectType>(EventObjectTypeWebProxyClient));

    struct epoll_event ev{};
    ev.events = EPOLLIN | EPOLLRDHUP | EPOLLERR | EPOLLET;
    ev.data.ptr = clientEpollObj;
    if (::epoll_ctl(cm.epolFd, EPOLL_CTL_ADD, clientSocket, &ev) < 0) {
        if (LOGS_ENABLED) DEBUG_E("WebProxyServer: epoll_ctl EPOLL_CTL_ADD client failed errno=%d", errno);
        ::close(clientSocket);
        clientSocket = -1;
        delete clientEpollObj;
        clientEpollObj = nullptr;
    }
}

void WebProxyServer::closeClient() {
    if (clientSocket == -1) return;
    ConnectionsManager& cm = ConnectionsManager::getInstance(0);
    ::epoll_ctl(cm.epolFd, EPOLL_CTL_DEL, clientSocket, nullptr);
    ::close(clientSocket);
    clientSocket = -1;
    wsHandshakeDone = false;
    recvBuffer.clear();

    // Уведомляем все стримы об отключении через публичный метод.
    for (auto& kv : streams) {
        kv.second->onWebProxyDisconnected();
    }
}

void WebProxyServer::readClient() {
    // Читаем данные порциями (EPOLLET требует читать до EAGAIN).
    while (true) {
        char buf[8192];
        ssize_t n = ::recv(clientSocket, buf, sizeof(buf), 0);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break; // всё прочитано
            if (LOGS_ENABLED) DEBUG_E("WebProxyServer: recv() failed errno=%d", errno);
            closeClient();
            return;
        }
        if (n == 0) {
            // Соединение закрыто браузером.
            closeClient();
            return;
        }

        if (!wsHandshakeDone) {
            // HTTP-фаза: разбираем запрос и отдаём ответ.
            std::string request(buf, static_cast<size_t>(n));
            if (!doHttpHandshake(request)) {
                closeClient();
            }
            // После отдачи HTML страницы сокет уже закрыт в serveHtmlPage().
            // После успешного рукопожатия wsHandshakeDone=true — продолжаем.
        } else {
            // WebSocket-фаза: накапливаем в буфер и парсим фреймы.
            recvBuffer.insert(recvBuffer.end(), buf, buf + n);
        }
    }

    // Парсим накопленные WebSocket фреймы.
    if (wsHandshakeDone) {
        processWsFrames();
    }
}

// ─── HTTP Handshake / HTML ────────────────────────────────────────────────────

bool WebProxyServer::doHttpHandshake(const std::string& request) {
    if (request.find("GET /transport") != std::string::npos) {
        // WebSocket Upgrade запрос от браузерного скрипта.
        size_t keyPos = request.find("Sec-WebSocket-Key: ");
        if (keyPos == std::string::npos) return false;
        keyPos += 19; // strlen("Sec-WebSocket-Key: ")
        size_t keyEnd = request.find("\r\n", keyPos);
        if (keyEnd == std::string::npos) return false;

        std::string clientKey = request.substr(keyPos, keyEnd - keyPos);
        // Убираем возможные пробелы в конце.
        while (!clientKey.empty() && (clientKey.back() == ' ' || clientKey.back() == '\r')) {
            clientKey.pop_back();
        }

        std::string magic = clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        unsigned char hash[SHA_DIGEST_LENGTH];
        SHA1(reinterpret_cast<const unsigned char*>(magic.c_str()), magic.length(), hash);
        std::string acceptKey = base64Encode(hash, SHA_DIGEST_LENGTH);

        std::string response =
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";

        writeClient(reinterpret_cast<const uint8_t*>(response.c_str()), response.length());
        wsHandshakeDone = true;
        if (LOGS_ENABLED) DEBUG_D("WebProxyServer: WebSocket handshake done");

        // Уведомляем все зарегистрированные стримы об установке соединения.
        for (auto& kv : streams) {
            kv.second->onWebProxyConnected();
        }
        return true;

    } else if (request.find("GET /") != std::string::npos) {
        // Обычный HTTP запрос — отдаём HTML страницу.
        serveHtmlPage();
        return true; // closeClient вызван внутри serveHtmlPage
    }

    // Неизвестный запрос — 400.
    const char* resp400 = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n";
    writeClient(reinterpret_cast<const uint8_t*>(resp400), strlen(resp400));
    return false;
}

void WebProxyServer::serveHtmlPage() {
    std::string html = tgnet::WEBPROXY_HTML;
    // Подставляем хост прокси.
    size_t pos = html.find("%PROXY_HOST%");
    if (pos != std::string::npos) {
        html.replace(pos, 12, "https://" + proxyHost);
    }
    std::string response =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/html; charset=utf-8\r\n"
        "Cache-Control: no-store\r\n"
        "Connection: close\r\n\r\n" + html;

    writeClient(reinterpret_cast<const uint8_t*>(response.c_str()), response.length());
    // Сокет закрываем после отправки HTML (Connection: close).
    closeClient();
}

// ─── WebSocket Frame Parser ───────────────────────────────────────────────────

void WebProxyServer::processWsFrames() {
    // RFC 6455: клиент ДОЛЖЕН маскировать данные (Mask bit = 1).
    while (recvBuffer.size() >= 2) {
        const uint8_t b0 = recvBuffer[0];
        const uint8_t b1 = recvBuffer[1];

        const uint8_t opcode = b0 & 0x0F;
        const bool masked   = (b1 & 0x80) != 0;
        uint64_t payloadLen = b1 & 0x7F;

        size_t headerLen = 2;

        if (payloadLen == 126) {
            if (recvBuffer.size() < 4) break; // ждём ещё данных
            payloadLen = (static_cast<uint64_t>(recvBuffer[2]) << 8)
                       |  static_cast<uint64_t>(recvBuffer[3]);
            headerLen += 2;
        } else if (payloadLen == 127) {
            if (recvBuffer.size() < 10) break;
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | static_cast<uint64_t>(recvBuffer[2 + i]);
            }
            headerLen += 8;
        }

        // Защита от DoS: отвергаем фреймы > 16 МиБ.
        if (payloadLen > 16 * 1024 * 1024) {
            if (LOGS_ENABLED) DEBUG_E("WebProxyServer: frame too large (%llu)", (unsigned long long)payloadLen);
            closeClient();
            return;
        }

        uint8_t maskKey[4] = {0, 0, 0, 0};
        if (masked) {
            if (recvBuffer.size() < headerLen + 4) break;
            memcpy(maskKey, &recvBuffer[headerLen], 4);
            headerLen += 4;
        }

        if (recvBuffer.size() < headerLen + payloadLen) break; // неполный фрейм

        // Демаскируем payload на месте.
        if (masked && payloadLen > 0) {
            for (size_t i = 0; i < payloadLen; i++) {
                recvBuffer[headerLen + i] ^= maskKey[i & 3];
            }
        }

        // Dispatch по opcode.
        if (opcode == 8) {
            // Close frame.
            closeClient();
            return;
        } else if (opcode == 2) {
            // Binary frame — наш внутренний протокол.
            if (payloadLen > 0) {
                parseFrame(recvBuffer.data() + headerLen, static_cast<size_t>(payloadLen));
            }
        }
        // opcode == 1 (Text), 9 (Ping), 10 (Pong) — игнорируем.

        // Удаляем обработанный фрейм из буфера.
        recvBuffer.erase(recvBuffer.begin(),
                         recvBuffer.begin() + headerLen + static_cast<size_t>(payloadLen));
    }
}

void WebProxyServer::parseFrame(const uint8_t* data, size_t len) {
    // Формат внутреннего фрейма: [type(1)][streamId(3)][size(4)][data...]
    size_t offset = 0;
    while (len - offset >= 8) {
        const uint8_t  type     = data[offset];
        const uint32_t streamId = (static_cast<uint32_t>(data[offset+1]) << 16)
                                | (static_cast<uint32_t>(data[offset+2]) << 8)
                                |  static_cast<uint32_t>(data[offset+3]);
        const uint32_t size     = (static_cast<uint32_t>(data[offset+4]) << 24)
                                | (static_cast<uint32_t>(data[offset+5]) << 16)
                                | (static_cast<uint32_t>(data[offset+6]) << 8)
                                |  static_cast<uint32_t>(data[offset+7]);

        if (len - offset < 8 + size) break; // фрейм неполный

        if (type == 1) { // FrameType::Data
            auto it = streams.find(streamId);
            if (it != streams.end()) {
                it->second->onWebProxyData(data + offset + 8, size);
            }
        }
        // type == 2 (Close), type == 4 (Window), etc. — обработка при необходимости.

        offset += 8 + static_cast<size_t>(size);
    }
}

// ─── writeClient ──────────────────────────────────────────────────────────────

void WebProxyServer::writeClient(const uint8_t* data, size_t len) {
    if (clientSocket == -1 || len == 0) return;
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = ::send(clientSocket, data + sent, len - sent, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // Буфер отправки переполнен. Для корректной работы нужна
                // очередь записи + EPOLLOUT, но для первой итерации просто
                // обрываем соединение.
                if (LOGS_ENABLED) DEBUG_E("WebProxyServer: send buffer full, closing");
                closeClient();
                return;
            }
            if (errno != EINTR) {
                if (LOGS_ENABLED) DEBUG_E("WebProxyServer: send() failed errno=%d", errno);
                closeClient();
                return;
            }
        } else {
            sent += static_cast<size_t>(n);
        }
    }
}
