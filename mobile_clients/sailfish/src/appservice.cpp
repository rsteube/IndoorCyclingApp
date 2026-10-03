#include "appservice.h"

#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QDBusError>
#include <QDBusMessage>
#include <QDebug>
#include <QFileInfo>
#include <QQuickItem>
#include <QQuickView>
#include <QUrl>

// The sandbox's own name for this app, OrganizationName.ApplicationName in the .desktop file's
// X-Sailjail section, which is the one name sailjail lets it own; the path is the name's.
const QString AppService::ServiceName = QStringLiteral("app.indoorbike.indoorbike");
const QString AppService::ObjectPath = QStringLiteral("/app/indoorbike/indoorbike");

namespace {

// A URI as QML takes it: the app's own scheme as it came, a file as a local path, or empty for
// anything else.
QString normalised(const QString &uri)
{
    const QUrl url(uri);
    if (url.scheme() == QLatin1String("indoorbike"))
        return uri;
    const QString path = url.isLocalFile() ? url.toLocalFile() : (url.scheme().isEmpty() ? uri : QString());
    if (path.isEmpty() || path.endsWith(QLatin1String(".qml")))
        return QString();
    return QFileInfo(path).absoluteFilePath();
}

} // namespace

AppService::AppService(QQuickView *view, QObject *parent)
    : QObject(parent)
    , m_view(view)
{
}

bool AppService::registerOnBus()
{
    QDBusConnection bus = QDBusConnection::sessionBus();
    if (!bus.registerObject(ObjectPath, this, QDBusConnection::ExportScriptableSlots)) {
        qWarning() << "App service: could not register" << ObjectPath << bus.lastError().message();
        return false;
    }
    // After the object, so a call queued for the name while the app was starting finds it.
    if (!bus.registerService(ServiceName)) {
        qWarning() << "App service: could not own" << ServiceName << bus.lastError().message();
        bus.unregisterObject(ObjectPath);
        return false;
    }
    return true;
}

QStringList AppService::openableArguments(const QStringList &arguments)
{
    QStringList uris;
    for (int i = 1; i < arguments.size(); ++i) {
        if (!normalised(arguments.at(i)).isEmpty())
            uris << arguments.at(i);
    }
    return uris;
}

bool AppService::forwardToRunning(const QStringList &uris)
{
    QDBusConnection bus = QDBusConnection::sessionBus();
    if (!bus.isConnected() || !bus.interface() || !bus.interface()->isServiceRegistered(ServiceName))
        return false;
    const QString interface = QStringLiteral("org.freedesktop.Application");
    QDBusMessage call = uris.isEmpty()
        ? QDBusMessage::createMethodCall(ServiceName, ObjectPath, interface, QStringLiteral("Activate"))
        : QDBusMessage::createMethodCall(ServiceName, ObjectPath, interface, QStringLiteral("Open"));
    if (!uris.isEmpty())
        call << uris;
    call << QVariantMap();
    bus.call(call, QDBus::Block, 2000);
    qInfo() << "Already running: handed over to that copy instead";
    return true;
}

void AppService::Activate(const QVariantMap &platformData)
{
    Q_UNUSED(platformData)
    raise();
}

void AppService::Open(const QStringList &uris, const QVariantMap &platformData)
{
    Q_UNUSED(platformData)
    raise();
    for (const QString &uri : uris) {
        const QString target = normalised(uri);
        if (target.isEmpty()) {
            qWarning() << "App service: nothing to open in" << uri;
            continue;
        }
        emit uriOpened(target);
    }
}

void AppService::ActivateAction(const QString &name, const QVariantList &parameter, const QVariantMap &platformData)
{
    // Lipstick does not call this yet, and the app has no actions; the spec asks for it all the same.
    Q_UNUSED(name)
    Q_UNUSED(parameter)
    Q_UNUSED(platformData)
    raise();
}

void AppService::raise()
{
    // Silica's own way to the foreground; a bare QWindow::raise() is ignored by the compositor.
    if (m_view && m_view->rootObject())
        QMetaObject::invokeMethod(m_view->rootObject(), "activate");
}
