#ifndef WEBPROXYSERVER_H
#define WEBPROXYSERVER_H

#include <string>
#include <map>
#include <vector>
#include <stdint.h>
#include "Defines.h"

// Forward declarations
class ConnectionSocket;
class NativeByteBuffer;
class EventObject;

class WebProxyServer {

public:
    static WebProxyServer& getInstance();

    // Запуск/остановка. Должны вызываться из сетевого потока ConnectionsManager.
    void start(const std::string& proxyHost);
    void stop();

    // Управление стримами (один стрим = одно MTProto-соединение).
    uint32_t registerStream(ConnectionSocket* socket);
    void closeStream(uint32_t streamId);

    // Отправка MTProto-данных от ConnectionSocket → браузер.
    void sendData(uint32_t streamId, NativeByteBuffer* buffer);

    // Геттеры для Java-слоя.
    int         getPort()  const { return listenPort; }
    const std::string& getToken() const { return token; }

    // Вызываются из EventObject::onEvent() в EventObject.cpp.
    void onListenEvent(uint32_t events);
    void onClientEvent(uint32_t events);

private:
    // Singleton: конструктор/деструктор приватные, копирование запрещено.
    WebProxyServer()  = default;
    ~WebProxyServer() { stop(); }
    WebProxyServer(const WebProxyServer&)            = delete;
    WebProxyServer& operator=(const WebProxyServer&) = delete;

    // ── Серверный сокет ──────────────────────────────────────
    int         listenSocket  = -1;
    int         listenPort    = 0;
    std::string proxyHost;
    std::string token;
    EventObject* listenEpollObj = nullptr;   // обёртка для epoll

    // ── Клиентское соединение (браузер) ──────────────────────
    int          clientSocket    = -1;
    bool         wsHandshakeDone = false;
    std::vector<uint8_t> recvBuffer;
    EventObject* clientEpollObj  = nullptr;  // обёртка для epoll

    // ── Стримы (id → ConnectionSocket*) ──────────────────────
    std::map<uint32_t, ConnectionSocket*> streams;
    uint32_t nextStreamId = 1;

    // ── Вспомогательные методы ───────────────────────────────
    void acceptClient();
    void readClient();
    void closeClient();
    void closeClientSocketOnly();
    void writeClient(const uint8_t* data, size_t len);
    void sendTextMessage(const std::string& text);
    void registerClientEpoll();

    // HTTP/WebSocket handshake.
    bool doHttpHandshake(const std::string& request);
    void serveHtmlPage();

    // Парсинг WebSocket-фреймов и внутреннего протокола.
    void processWsFrames();
    void parseFrame(const uint8_t* data, size_t len);
};

#endif // WEBPROXYSERVER_H
