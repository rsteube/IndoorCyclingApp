#ifndef INDOORBIKECORE_H
#define INDOORBIKECORE_H

#include <QHash>
#include <QJSValue>
#include <QJsonObject>
#include <QObject>
#include <QQmlPropertyMap>
#include <QString>
#include <QVariant>

class KeyedListModel;
class QJSEngine;

// The app's one handle on the shared Kotlin, compiled for the phone into libindoorbikecore.so by
// core/. Put in the QML context as `core`.
//
// The boundary is a bus (core/src/linuxMain/.../sailfish/Bridge.kt and Bus.kt): commands go in as
// JSON and are answered at once, and everything slow or changing comes back as an event, on the
// main thread.
//
// Two kinds of topic. One without a dot ("trainer", "ride") is state: the core sends all of it
// whenever it changes, and the latest is kept in `state` for QML to bind to, and, when it is a
// list of objects with ids, as a model from list(). One with a dot ("ride.saved") is an event,
// delivered once through eventReceived and not kept.
//
// Three ways to ask: send() for a quick command and its answer; request() for slow work, whose
// answer reaches a JavaScript callback later; and log() to put a line in the diagnostics log a bug
// report attaches.
class IndoorBikeCore : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool started READ started CONSTANT)
    // QObject to QML, which cannot read a property typed as QQmlPropertyMap; its keys still bind.
    Q_PROPERTY(QObject *state READ state CONSTANT)

public:
    struct Folders {
        QString data;
        QString config;
        QString cache;
        // The package's own read-only files: /usr/share/harbour-indoorbike.
        QString package;
    };

    IndoorBikeCore(const Folders &folders, const QString &version, bool debug, QObject *parent = nullptr);
    ~IndoorBikeCore() override;

    static IndoorBikeCore *instance();

    bool started() const { return m_started; }
    QQmlPropertyMap *state() const { return m_state; }

    // The engine request() callbacks are called in.
    void setEngine(QJSEngine *engine) { m_engine = engine; }

    // One command, and the core's answer: {"ok": true, "value": ...} or {"ok": false, "message": ...}.
    QJsonObject send(const QJsonObject &command);
    Q_INVOKABLE QVariantMap send(const QVariantMap &command);

    // Slow work: the answer, shaped as send()'s, reaches callback(reply) later on the main thread.
    Q_INVOKABLE void request(const QVariantMap &command, const QJSValue &callback);

    // A state topic's list, as a model that keeps its rows across updates. Never null.
    Q_INVOKABLE KeyedListModel *list(const QString &topic);

    Q_INVOKABLE void log(const QString &message, const QString &level = QString());

signals:
    void eventReceived(const QString &topic, const QVariant &payload);

private:
    friend void indoorbikeDeliver(const char *json);
    friend class TestCore;

    static void setInstance(IndoorBikeCore *core);

    // Called on the main thread with one event envelope, {"topic": ..., "payload": ...}.
    Q_INVOKABLE void deliver(const QString &json);
    void answer(const QString &id, const QVariantMap &reply);

    bool m_started = false;
    QQmlPropertyMap *m_state;
    QHash<QString, KeyedListModel *> m_lists;
    QHash<QString, QJSValue> m_pending;
    QJSEngine *m_engine = nullptr;
    int m_nextRequest = 0;
};

#endif // INDOORBIKECORE_H
