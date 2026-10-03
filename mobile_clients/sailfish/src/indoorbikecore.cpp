#include "indoorbikecore.h"

#include <QDebug>
#include <QJsonDocument>
#include <QMetaObject>

#include "libindoorbikecore_api.h"

// The half of IndoorBikeCore that calls into libindoorbikecore. The rest, which only turns events
// into state, is in indoorbikecorestate.cpp, so the tests can run it without the library.

namespace {

// Kotlin/Native exposes one entry point and hangs everything else off it. The first call also
// starts the Kotlin runtime, so a library that is broken shows itself here.
libindoorbikecore_ExportedSymbols *symbols()
{
    static libindoorbikecore_ExportedSymbols *cached = libindoorbikecore_symbols();
    return cached;
}

#define BRIDGE symbols()->kotlin.root.app.indoorbike.shared.sailfish

// Every string the library returns belongs to it: copy it, then hand the original back.
QString take(const char *owned)
{
    if (!owned)
        return QString();
    const QString copy = QString::fromUtf8(owned);
    symbols()->DisposeString(owned);
    return copy;
}

QJsonObject failure(const QString &message)
{
    return QJsonObject{ { QStringLiteral("ok"), false }, { QStringLiteral("message"), message } };
}

} // namespace

// The core calls this on one of its own threads. It only copies the string and queues it to the
// main thread: touching a QObject, let alone QML, from here would be a race, and waiting on the
// main thread could deadlock against a command the main thread is waiting on.
void indoorbikeDeliver(const char *json)
{
    IndoorBikeCore *core = IndoorBikeCore::instance();
    if (!core || !json)
        return;
    QMetaObject::invokeMethod(core, "deliver", Qt::QueuedConnection, Q_ARG(QString, QString::fromUtf8(json)));
}

IndoorBikeCore::IndoorBikeCore(const Folders &folders, const QString &version, bool debug, QObject *parent)
    : QObject(parent)
    , m_state(new QQmlPropertyMap(this))
{
    if (!symbols()) {
        qWarning() << "Core: libindoorbikecore did not load";
        return;
    }
    setInstance(this);
    // Before starting: the core publishes its first state as it starts, and an event with no
    // subscriber is dropped. The header types the callback as void*; its real signature is
    // void (const char *), which Bridge.kt's EventSink declares.
    BRIDGE.indoorbikeSubscribe(reinterpret_cast<void *>(&indoorbikeDeliver));
    m_started = BRIDGE.indoorbikeStart(folders.data.toUtf8().constData(), folders.config.toUtf8().constData(),
                                       folders.cache.toUtf8().constData(), folders.package.toUtf8().constData(),
                                       version.toUtf8().constData(), debug);
    if (!m_started) {
        qWarning() << "Core: did not start";
        return;
    }
    qInfo() << "Core: started";
}

IndoorBikeCore::~IndoorBikeCore()
{
    if (m_started)
        BRIDGE.indoorbikeStop();
    if (instance() == this)
        setInstance(nullptr);
}

QJsonObject IndoorBikeCore::send(const QJsonObject &command)
{
    if (!m_started)
        return failure(QStringLiteral("The shared core is not running."));
    const QByteArray request = QJsonDocument(command).toJson(QJsonDocument::Compact);
    const QString reply = take(BRIDGE.indoorbikeSend(request.constData()));
    const QJsonDocument document = QJsonDocument::fromJson(reply.toUtf8());
    if (!document.isObject())
        return failure(QStringLiteral("The shared core answered with something that is not JSON."));
    return document.object();
}
