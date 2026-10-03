#include "bluetransport.h"

#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusServiceWatcher>
#include <QDebug>
#include <QJsonArray>
#include <QJsonObject>

#include "indoorbikecore.h"
#include "notifypipe.h"

namespace {

const QString PropertyAddress = QStringLiteral("Address");
const QString PropertyAlias = QStringLiteral("Alias");
const QString PropertyConnected = QStringLiteral("Connected");
const QString PropertyDevice = QStringLiteral("Device");
const QString PropertyFlags = QStringLiteral("Flags");
const QString PropertyName = QStringLiteral("Name");
const QString PropertyPowered = QStringLiteral("Powered");
const QString PropertyRssi = QStringLiteral("RSSI");
const QString PropertyService = QStringLiteral("Service");
const QString PropertyServicesResolved = QStringLiteral("ServicesResolved");
const QString PropertyUuid = QStringLiteral("UUID");
const QString PropertyUuids = QStringLiteral("UUIDs");
const QString PropertyValue = QStringLiteral("Value");

const QString StateConnecting = QStringLiteral("Connecting");
const QString StateConnected = QStringLiteral("Connected");
const QString StateDisconnecting = QStringLiteral("Disconnecting");
const QString StateDisconnected = QStringLiteral("Disconnected");

// Long enough for a trainer that is waking, short enough that a missing one is given up on
// before the shared reconnect loop's own window would have moved on.
const int ConnectTimeoutMs = 20000;
const int CallTimeoutMs = 10000;

QJsonObject command(const QString &name)
{
    return QJsonObject{ { QStringLiteral("cmd"), name } };
}

QString errorText(const QDBusError &error)
{
    return error.name() + QLatin1String(": ") + error.message();
}

} // namespace

BlueTransport::BlueTransport(IndoorBikeCore *core, QObject *parent)
    : QObject(parent)
    , m_core(core)
    , m_bus(QDBusConnection::systemBus())
    , m_serviceWatcher(new QDBusServiceWatcher(Bluez::Service, QDBusConnection::systemBus(),
                                               QDBusServiceWatcher::WatchForRegistration
                                                   | QDBusServiceWatcher::WatchForUnregistration,
                                               this))
{
    // Before any connect below: QtDBus refuses a slot whose argument types it cannot marshal.
    Bluez::registerTypes();

    connect(m_core, &IndoorBikeCore::eventReceived, this, &BlueTransport::onEvent);
    connect(m_serviceWatcher, &QDBusServiceWatcher::serviceRegistered, this, &BlueTransport::onServiceRegistered);
    connect(m_serviceWatcher, &QDBusServiceWatcher::serviceUnregistered, this,
            &BlueTransport::onServiceUnregistered);

    if (!m_bus.isConnected()) {
        qWarning() << "Bluetooth: no system bus:" << m_bus.lastError().message();
        reportAdapter();
        return;
    }

    bool subscribed = m_bus.connect(Bluez::Service, QStringLiteral("/"), Bluez::ObjectManagerInterface,
                                    QStringLiteral("InterfacesAdded"), this,
                                    SLOT(onInterfacesAdded(QDBusObjectPath,InterfaceList)));
    subscribed &= m_bus.connect(Bluez::Service, QStringLiteral("/"), Bluez::ObjectManagerInterface,
                                QStringLiteral("InterfacesRemoved"), this,
                                SLOT(onInterfacesRemoved(QDBusObjectPath,QStringList)));
    // No path: one subscription covers the adapter, every device and every characteristic.
    subscribed &= m_bus.connect(Bluez::Service, QString(), Bluez::PropertiesInterface,
                                QStringLiteral("PropertiesChanged"), this,
                                SLOT(onPropertiesChanged(QString,QVariantMap,QStringList,QDBusMessage)));
    // BlueZ says why a link ended, which is the first question when a trainer drops.
    subscribed &= m_bus.connect(Bluez::Service, QString(), Bluez::DeviceInterface, QStringLiteral("Disconnected"),
                                this, SLOT(onDeviceDisconnected(QString,QString,QDBusMessage)));
    if (!subscribed)
        qWarning() << "Bluetooth: could not subscribe to BlueZ:" << m_bus.lastError().message();

    loadObjects();
}

BlueTransport::~BlueTransport()
{
    // Sockets first, while the links are still up; see the class comment.
    const QStringList addresses = m_links.keys();
    for (const QString &address : addresses)
        releaseSubscriptions(address);
    // A trainer carries one link, and one left up by a closed app would hide it from every other
    // app. This blocks, briefly, once, at exit.
    for (const QString &address : addresses) {
        const QString path = m_links.value(address).path;
        if (!path.isEmpty() && properties(path, Bluez::DeviceInterface).value(PropertyConnected).toBool())
            m_bus.call(methodCall(path, Bluez::DeviceInterface, QStringLiteral("Disconnect")), QDBus::Block, 3000);
    }
    if (m_discovering && !m_adapterPath.isEmpty())
        m_bus.call(methodCall(m_adapterPath, Bluez::AdapterInterface, QStringLiteral("StopDiscovery")),
                   QDBus::Block, 1000);
}

int BlueTransport::propertyBits(const QStringList &flags)
{
    static const QHash<QString, int> bits{
        { QStringLiteral("broadcast"), 0x01 },
        { QStringLiteral("read"), 0x02 },
        { QStringLiteral("write-without-response"), 0x04 },
        { QStringLiteral("write"), 0x08 },
        { QStringLiteral("notify"), 0x10 },
        { QStringLiteral("indicate"), 0x20 },
        { QStringLiteral("authenticated-signed-writes"), 0x40 },
        { QStringLiteral("extended-properties"), 0x80 },
    };
    int value = 0;
    for (const QString &flag : flags)
        value |= bits.value(flag, 0);
    return value;
}

// --- Requests from the core ----------------------------------------------------------------

void BlueTransport::onEvent(const QString &topic, const QVariant &payload)
{
    if (!topic.startsWith(QLatin1String("ble.")))
        return;
    const QVariantMap args = payload.toMap();
    if (topic == QLatin1String("ble.discovery"))
        setDiscovery(args.value(QStringLiteral("on")).toBool(), args.value(QStringLiteral("uuids")).toStringList());
    else if (topic == QLatin1String("ble.connect"))
        connectDevice(args.value(QStringLiteral("address")).toString());
    else if (topic == QLatin1String("ble.disconnect"))
        disconnectDevice(args.value(QStringLiteral("address")).toString());
    else if (topic == QLatin1String("ble.subscribe"))
        subscribe(args, args.value(QStringLiteral("on")).toBool());
    else if (topic == QLatin1String("ble.write"))
        write(args);
    else if (topic == QLatin1String("ble.read"))
        read(args);
}

void BlueTransport::setDiscovery(bool on, const QStringList &uuids)
{
    m_wantDiscovery = on;
    m_discoveryUuids = uuids;
    if (!on) {
        if (m_discovering && !m_adapterPath.isEmpty()) {
            QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
                m_bus.asyncCall(methodCall(m_adapterPath, Bluez::AdapterInterface, QStringLiteral("StopDiscovery"))),
                this);
            connect(watcher, &QDBusPendingCallWatcher::finished, watcher, &QObject::deleteLater);
        }
        m_discovering = false;
        return;
    }
    startDiscovery();
}

void BlueTransport::startDiscovery()
{
    if (!m_wantDiscovery || m_adapterPath.isEmpty()
        || !properties(m_adapterPath, Bluez::AdapterInterface).value(PropertyPowered).toBool())
        return;

    QDBusMessage filter = methodCall(m_adapterPath, Bluez::AdapterInterface, QStringLiteral("SetDiscoveryFilter"));
    QVariantMap options;
    options.insert(QStringLiteral("Transport"), QStringLiteral("le"));
    if (!m_discoveryUuids.isEmpty())
        options.insert(PropertyUuids, m_discoveryUuids);
    filter << options;
    QDBusPendingCallWatcher *filterCall = new QDBusPendingCallWatcher(m_bus.asyncCall(filter), this);
    connect(filterCall, &QDBusPendingCallWatcher::finished, this, [](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        if (call->isError())
            qWarning() << "Bluetooth: discovery filter refused:" << errorText(call->error());
    });

    // Sent after the filter on the same connection, so BlueZ applies the filter first.
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
        m_bus.asyncCall(methodCall(m_adapterPath, Bluez::AdapterInterface, QStringLiteral("StartDiscovery"))), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        if (call->isError() && call->error().name() != Bluez::ErrorInProgress) {
            qWarning() << "Bluetooth: discovery did not start:" << errorText(call->error());
            m_discovering = false;
        }
    });
    m_discovering = true;
}

void BlueTransport::connectDevice(const QString &address)
{
    const QString path = devicePathFor(address);
    if (path.isEmpty()) {
        reportLink(address, StateDisconnected, QStringLiteral("Bluetooth does not know that device"));
        return;
    }
    Link &link = m_links[address];
    link.path = path;
    link.lastError.clear();
    const QVariantMap device = properties(path, Bluez::DeviceInterface);
    if (device.value(PropertyConnected).toBool() && device.value(PropertyServicesResolved).toBool()) {
        reportLink(address, StateConnected);
        return;
    }
    reportLink(address, StateConnecting);

    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
        m_bus.asyncCall(methodCall(path, Bluez::DeviceInterface, QStringLiteral("Connect")), ConnectTimeoutMs), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, address](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        if (!call->isError() || call->error().name() == Bluez::ErrorAlreadyConnected)
            return;
        qWarning() << "Bluetooth: connect failed:" << errorText(call->error());
        if (m_links.contains(address) && m_links.value(address).state != StateConnected) {
            releaseSubscriptions(address);
            reportLink(address, StateDisconnected, call->error().message());
        }
    });
}

void BlueTransport::disconnectDevice(const QString &address)
{
    // Sockets first; see the class comment.
    releaseSubscriptions(address);
    const QString path = m_links.value(address).path;
    const bool connected = !path.isEmpty()
        && properties(path, Bluez::DeviceInterface).value(PropertyConnected).toBool();
    if (path.isEmpty()) {
        reportLink(address, StateDisconnected);
        return;
    }
    reportLink(address, connected ? StateDisconnecting : StateDisconnected);
    // Also ends a connect still in progress.
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
        m_bus.asyncCall(methodCall(path, Bluez::DeviceInterface, QStringLiteral("Disconnect"))), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, watcher, &QObject::deleteLater);
    if (!connected)
        m_links.remove(address);
}

void BlueTransport::subscribe(const QVariantMap &args, bool on)
{
    const QString address = args.value(QStringLiteral("address")).toString();
    const QString uuid = args.value(QStringLiteral("char")).toString();
    const QString path = characteristicPath(m_links.value(address).path, args.value(QStringLiteral("service")).toString(),
                                            uuid);
    if (path.isEmpty()) {
        if (on)
            subscribeFailed(address, uuid, QStringLiteral("No such characteristic"));
        return;
    }

    if (!on) {
        const Subscription subscription = m_subscriptions.take(path);
        delete subscription.pipe;
        if (subscription.overDBus) {
            QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
                m_bus.asyncCall(methodCall(path, Bluez::GattCharacteristicInterface, QStringLiteral("StopNotify"))),
                this);
            connect(watcher, &QDBusPendingCallWatcher::finished, watcher, &QObject::deleteLater);
        }
        return;
    }
    if (m_subscriptions.contains(path))
        return;

    Subscription &subscription = m_subscriptions[path];
    subscription.address = address;
    subscription.uuid = uuid;

    const QStringList flags = properties(path, Bluez::GattCharacteristicInterface).value(PropertyFlags).toStringList();
    if (!flags.contains(QStringLiteral("notify"))) {
        // Indicate only, like the FTMS control point: AcquireNotify does not take those.
        startNotifyOverDBus(path);
        return;
    }
    NotifyPipe *pipe = new NotifyPipe(m_bus, path, this);
    subscription.pipe = pipe;
    connect(pipe, &NotifyPipe::notifying, this, [this, path](int mtu) {
        const Subscription subscription = m_subscriptions.value(path);
        qInfo() << "Bluetooth: notifications through a socket, MTU" << mtu;
        m_core->send(QJsonObject{ { QStringLiteral("cmd"), QStringLiteral("ble.subscribed") },
                                  { QStringLiteral("address"), subscription.address },
                                  { QStringLiteral("char"), subscription.uuid } });
    });
    connect(pipe, &NotifyPipe::notification, this, [this, path](const QByteArray &frame) {
        reportValue(m_subscriptions.value(path), frame);
    });
    connect(pipe, &NotifyPipe::refused, this, [this, path, pipe](const QString &reason) {
        qInfo() << "Bluetooth: no notify socket (" << reason << "), using D-Bus";
        if (!m_subscriptions.contains(path))
            return;
        m_subscriptions[path].pipe = nullptr;
        pipe->deleteLater();
        startNotifyOverDBus(path);
    });
    pipe->acquire();
}

void BlueTransport::startNotifyOverDBus(const QString &path)
{
    m_subscriptions[path].overDBus = true;
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
        m_bus.asyncCall(methodCall(path, Bluez::GattCharacteristicInterface, QStringLiteral("StartNotify"))), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, path](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        if (!m_subscriptions.contains(path))
            return;
        const Subscription subscription = m_subscriptions.value(path);
        if (call->isError()) {
            m_subscriptions.remove(path);
            subscribeFailed(subscription.address, subscription.uuid, errorText(call->error()));
            return;
        }
        m_core->send(QJsonObject{ { QStringLiteral("cmd"), QStringLiteral("ble.subscribed") },
                                  { QStringLiteral("address"), subscription.address },
                                  { QStringLiteral("char"), subscription.uuid } });
    });
}

void BlueTransport::write(const QVariantMap &args)
{
    const QString token = args.value(QStringLiteral("token")).toString();
    const QString path = characteristicPath(m_links.value(args.value(QStringLiteral("address")).toString()).path,
                                            args.value(QStringLiteral("service")).toString(),
                                            args.value(QStringLiteral("char")).toString());
    if (path.isEmpty()) {
        done(token, false, QByteArray(), QStringLiteral("No such characteristic"));
        return;
    }
    QDBusMessage message = methodCall(path, Bluez::GattCharacteristicInterface, QStringLiteral("WriteValue"));
    QVariantMap options;
    options.insert(QStringLiteral("type"), args.value(QStringLiteral("response")).toBool() ? QStringLiteral("request")
                                                                                         : QStringLiteral("command"));
    message << QByteArray::fromBase64(args.value(QStringLiteral("b64")).toString().toLatin1()) << options;
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(m_bus.asyncCall(message, CallTimeoutMs), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, token](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        if (call->isError())
            done(token, false, QByteArray(), errorText(call->error()));
        else
            done(token, true);
    });
}

void BlueTransport::read(const QVariantMap &args)
{
    const QString token = args.value(QStringLiteral("token")).toString();
    const QString path = characteristicPath(m_links.value(args.value(QStringLiteral("address")).toString()).path,
                                            args.value(QStringLiteral("service")).toString(),
                                            args.value(QStringLiteral("char")).toString());
    if (path.isEmpty()) {
        done(token, false, QByteArray(), QStringLiteral("No such characteristic"));
        return;
    }
    QDBusMessage message = methodCall(path, Bluez::GattCharacteristicInterface, QStringLiteral("ReadValue"));
    message << QVariantMap();
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(m_bus.asyncCall(message, CallTimeoutMs), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, token](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        const QDBusPendingReply<QByteArray> reply = *call;
        if (reply.isError())
            done(token, false, QByteArray(), errorText(reply.error()));
        else
            done(token, true, reply.value());
    });
}

// --- BlueZ's object tree --------------------------------------------------------------------

void BlueTransport::loadObjects()
{
    const QDBusMessage call = QDBusMessage::createMethodCall(Bluez::Service, QStringLiteral("/"),
                                                             Bluez::ObjectManagerInterface,
                                                             QStringLiteral("GetManagedObjects"));
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(m_bus.asyncCall(call), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, &BlueTransport::onObjectsLoaded);
}

void BlueTransport::onObjectsLoaded(QDBusPendingCallWatcher *watcher)
{
    watcher->deleteLater();
    const QDBusPendingReply<ManagedObjectList> reply = *watcher;
    if (reply.isError()) {
        qWarning() << "Bluetooth: BlueZ did not list its objects:" << errorText(reply.error());
        m_adapterPath.clear();
        reportAdapter();
        return;
    }
    m_objects.clear();
    const ManagedObjectList objects = reply.value();
    for (ManagedObjectList::const_iterator it = objects.constBegin(); it != objects.constEnd(); ++it)
        m_objects.insert(it.key().path(), it.value());
    m_loaded = true;
    reportAdapter();
    startDiscovery();
}

void BlueTransport::onInterfacesAdded(const QDBusObjectPath &path, const InterfaceList &interfaces)
{
    InterfaceList &object = m_objects[path.path()];
    for (InterfaceList::const_iterator it = interfaces.constBegin(); it != interfaces.constEnd(); ++it)
        object.insert(it.key(), it.value());
    if (interfaces.contains(Bluez::AdapterInterface))
        reportAdapter();
    if (interfaces.contains(Bluez::DeviceInterface)) {
        reportAdvert(path.path());
        deviceChanged(path.path(), interfaces.value(Bluez::DeviceInterface));
    }
    // A link's services appearing after Connected: ServicesResolved follows them, but a service
    // can also arrive late on a slow device.
    if (interfaces.contains(Bluez::GattCharacteristicInterface) || interfaces.contains(Bluez::GattServiceInterface)) {
        for (QHash<QString, Link>::const_iterator it = m_links.constBegin(); it != m_links.constEnd(); ++it) {
            if (path.path().startsWith(it.value().path + QLatin1Char('/')))
                deviceChanged(it.value().path, QVariantMap());
        }
    }
}

void BlueTransport::onInterfacesRemoved(const QDBusObjectPath &path, const QStringList &interfaces)
{
    const QString key = path.path();
    QMap<QString, InterfaceList>::iterator object = m_objects.find(key);
    if (object == m_objects.end())
        return;
    for (const QString &interface : interfaces)
        object->remove(interface);
    if (object->isEmpty())
        m_objects.erase(object);

    if (interfaces.contains(Bluez::AdapterInterface)) {
        if (key == m_adapterPath)
            m_adapterPath.clear();
        reportAdapter();
    }
    if (interfaces.contains(Bluez::DeviceInterface)) {
        for (QHash<QString, Link>::iterator it = m_links.begin(); it != m_links.end(); ++it) {
            if (it.value().path == key) {
                const QString address = it.key();
                releaseSubscriptions(address);
                reportLink(address, StateDisconnected, QStringLiteral("The device went away"));
                m_links.remove(address);
                break;
            }
        }
    }
}

void BlueTransport::onPropertiesChanged(const QString &interface, const QVariantMap &changed,
                                        const QStringList &invalidated, const QDBusMessage &message)
{
    const QString path = message.path();
    QMap<QString, InterfaceList>::iterator object = m_objects.find(path);
    if (object == m_objects.end())
        return;
    InterfaceList::iterator props = object->find(interface);
    if (props == object->end())
        return;
    for (QVariantMap::const_iterator it = changed.constBegin(); it != changed.constEnd(); ++it)
        props->insert(it.key(), it.value());
    for (const QString &name : invalidated)
        props->remove(name);

    if (interface == Bluez::GattCharacteristicInterface) {
        // A notification over D-Bus: BlueZ reports it as the Value changing.
        if (changed.contains(PropertyValue)) {
            const QHash<QString, Subscription>::const_iterator subscription = m_subscriptions.constFind(path);
            if (subscription != m_subscriptions.constEnd() && subscription->overDBus)
                reportValue(*subscription, changed.value(PropertyValue).toByteArray());
        }
        return;
    }
    if (interface == Bluez::AdapterInterface) {
        reportAdapter();
        if (changed.contains(PropertyPowered)) {
            m_discovering = false;
            startDiscovery();
        }
        return;
    }
    if (interface == Bluez::DeviceInterface) {
        // A fresh RSSI is BlueZ's sign that it has just heard the device advertise.
        if (changed.contains(PropertyRssi))
            reportAdvert(path);
        deviceChanged(path, changed);
    }
}

void BlueTransport::onDeviceDisconnected(const QString &reason, const QString &message, const QDBusMessage &signal)
{
    const QString address = addressOfPath(signal.path());
    if (!m_links.contains(address))
        return;
    qWarning() << "Bluetooth: link ended:" << reason << message;
    m_links[address].lastError = message.isEmpty() ? reason : message;
}

void BlueTransport::onServiceRegistered()
{
    qInfo() << "Bluetooth: BlueZ is back";
    loadObjects();
}

void BlueTransport::onServiceUnregistered()
{
    qWarning() << "Bluetooth: BlueZ went away";
    const QStringList addresses = m_links.keys();
    for (const QString &address : addresses) {
        releaseSubscriptions(address);
        reportLink(address, StateDisconnected, QStringLiteral("Bluetooth stopped"));
    }
    m_links.clear();
    m_objects.clear();
    m_adapterPath.clear();
    m_discovering = false;
    reportAdapter();
}

// A device's Connected or ServicesResolved moved, or its table grew: tell the core what its
// link is now. A link is Connected only once the services are resolved, because the managers
// read the table the moment they see it.
void BlueTransport::deviceChanged(const QString &path, const QVariantMap &changed)
{
    const QString address = addressOfPath(path);
    QHash<QString, Link>::iterator link = m_links.find(address);
    if (link == m_links.end())
        return;
    const QVariantMap device = properties(path, Bluez::DeviceInterface);
    const bool connected = device.value(PropertyConnected).toBool();
    const bool resolved = device.value(PropertyServicesResolved).toBool();

    if (connected && resolved) {
        if (link->state != StateConnected)
            reportLink(address, StateConnected);
        return;
    }
    // Connected only ever reads false here after it was true: a drop, or the end of a disconnect.
    if (changed.contains(PropertyConnected) && !connected) {
        const QString error = link->lastError;
        releaseSubscriptions(address);
        m_links.erase(link);
        reportLink(address, StateDisconnected, error);
    }
}

// --- Reports to the core --------------------------------------------------------------------

void BlueTransport::reportAdapter()
{
    if (m_adapterPath.isEmpty() || !m_objects.contains(m_adapterPath)) {
        m_adapterPath.clear();
        for (QMap<QString, InterfaceList>::const_iterator it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
            if (it.value().contains(Bluez::AdapterInterface)) {
                m_adapterPath = it.key();
                break;
            }
        }
    }
    QString reason;
    if (!m_bus.isConnected())
        reason = QStringLiteral("no system bus");
    else if (!m_loaded)
        reason = QStringLiteral("BlueZ is not running");
    else if (m_adapterPath.isEmpty())
        reason = QStringLiteral("no adapter");
    else if (!properties(m_adapterPath, Bluez::AdapterInterface).value(PropertyPowered).toBool())
        reason = QStringLiteral("Off");
    // Only changes: every PropertiesChanged on the adapter (discovery starting and stopping
    // included) comes through here.
    const QString state = reason.isEmpty() ? QStringLiteral("on") : reason;
    if (state == m_reportedAdapter)
        return;
    m_reportedAdapter = state;
    qInfo() << "Bluetooth:" << state;
    QJsonObject report = command(QStringLiteral("ble.adapter"));
    report.insert(QStringLiteral("available"), reason.isEmpty());
    report.insert(QStringLiteral("reason"), reason);
    m_core->send(report);
}

void BlueTransport::reportAdvert(const QString &path)
{
    if (!m_discovering)
        return;
    const QVariantMap device = properties(path, Bluez::DeviceInterface);
    if (!device.contains(PropertyRssi))
        return;
    QJsonObject advert = command(QStringLiteral("ble.advert"));
    advert.insert(QStringLiteral("address"), device.value(PropertyAddress).toString());
    const QString name = device.value(PropertyName).toString();
    if (!name.isEmpty())
        advert.insert(QStringLiteral("name"), name);
    // A device that sends no name gets its address, written with dashes, as its alias. That is
    // not a name, and a pairing list showing it would only look like one.
    const QString alias = device.value(PropertyAlias).toString();
    QString addressAsAlias = device.value(PropertyAddress).toString();
    addressAsAlias.replace(QLatin1Char(':'), QLatin1Char('-'));
    if (!alias.isEmpty() && alias.compare(addressAsAlias, Qt::CaseInsensitive) != 0)
        advert.insert(QStringLiteral("alias"), alias);
    advert.insert(QStringLiteral("rssi"), device.value(PropertyRssi).toInt());
    advert.insert(QStringLiteral("uuids"), QJsonArray::fromStringList(device.value(PropertyUuids).toStringList()));
    m_core->send(advert);
}

void BlueTransport::reportLink(const QString &address, const QString &state, const QString &error)
{
    if (m_links.contains(address))
        m_links[address].state = state;
    QJsonObject report = command(QStringLiteral("ble.link"));
    report.insert(QStringLiteral("address"), address);
    report.insert(QStringLiteral("state"), state);
    if (!error.isEmpty())
        report.insert(QStringLiteral("error"), error);
    if (state == StateConnected)
        report.insert(QStringLiteral("services"), QJsonArray::fromVariantList(servicesOf(m_links.value(address).path)));
    m_core->send(report);
}

void BlueTransport::reportValue(const Subscription &subscription, const QByteArray &value)
{
    if (subscription.address.isEmpty())
        return;
    QJsonObject report = command(QStringLiteral("ble.value"));
    report.insert(QStringLiteral("address"), subscription.address);
    report.insert(QStringLiteral("char"), subscription.uuid);
    report.insert(QStringLiteral("b64"), QString::fromLatin1(value.toBase64()));
    m_core->send(report);
}

void BlueTransport::subscribeFailed(const QString &address, const QString &uuid, const QString &message)
{
    QJsonObject report = command(QStringLiteral("ble.subscribeFailed"));
    report.insert(QStringLiteral("address"), address);
    report.insert(QStringLiteral("char"), uuid);
    report.insert(QStringLiteral("message"), message);
    m_core->send(report);
}

void BlueTransport::done(const QString &token, bool ok, const QByteArray &value, const QString &message)
{
    QJsonObject report = command(QStringLiteral("ble.done"));
    report.insert(QStringLiteral("token"), token);
    report.insert(QStringLiteral("ok"), ok);
    if (ok)
        report.insert(QStringLiteral("b64"), QString::fromLatin1(value.toBase64()));
    else
        report.insert(QStringLiteral("message"), message);
    m_core->send(report);
}

void BlueTransport::releaseSubscriptions(const QString &address)
{
    for (QHash<QString, Subscription>::iterator it = m_subscriptions.begin(); it != m_subscriptions.end();) {
        if (it.value().address == address) {
            delete it.value().pipe;
            it = m_subscriptions.erase(it);
        } else {
            ++it;
        }
    }
}

// --- Lookups --------------------------------------------------------------------------------

QString BlueTransport::devicePathFor(const QString &address) const
{
    for (QMap<QString, InterfaceList>::const_iterator it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const QVariantMap device = it.value().value(Bluez::DeviceInterface);
        if (device.value(PropertyAddress).toString().compare(address, Qt::CaseInsensitive) == 0)
            return it.key();
    }
    return QString();
}

QString BlueTransport::addressOfPath(const QString &path) const
{
    return properties(path, Bluez::DeviceInterface).value(PropertyAddress).toString();
}

QString BlueTransport::characteristicPath(const QString &devicePath, const QString &service, const QString &uuid) const
{
    if (devicePath.isEmpty())
        return QString();
    const QString prefix = devicePath + QLatin1Char('/');
    for (QMap<QString, InterfaceList>::const_iterator it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        if (!it.key().startsWith(prefix))
            continue;
        const QVariantMap characteristic = it.value().value(Bluez::GattCharacteristicInterface);
        if (characteristic.isEmpty() || !Bluez::sameUuid(characteristic.value(PropertyUuid).toString(), uuid))
            continue;
        const QString servicePath = characteristic.value(PropertyService).value<QDBusObjectPath>().path();
        if (Bluez::sameUuid(properties(servicePath, Bluez::GattServiceInterface).value(PropertyUuid).toString(), service))
            return it.key();
    }
    return QString();
}

QVariantList BlueTransport::servicesOf(const QString &devicePath) const
{
    QVariantList services;
    if (devicePath.isEmpty())
        return services;
    const QString prefix = devicePath + QLatin1Char('/');
    for (QMap<QString, InterfaceList>::const_iterator it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        if (!it.key().startsWith(prefix) || !it.value().contains(Bluez::GattServiceInterface))
            continue;
        const QString servicePath = it.key();
        QVariantList characteristics;
        for (QMap<QString, InterfaceList>::const_iterator c = m_objects.constBegin(); c != m_objects.constEnd(); ++c) {
            const QVariantMap characteristic = c.value().value(Bluez::GattCharacteristicInterface);
            if (characteristic.isEmpty()
                || characteristic.value(PropertyService).value<QDBusObjectPath>().path() != servicePath)
                continue;
            characteristics.append(QVariantMap{
                { QStringLiteral("uuid"), characteristic.value(PropertyUuid).toString() },
                { QStringLiteral("props"), propertyBits(characteristic.value(PropertyFlags).toStringList()) },
            });
        }
        services.append(QVariantMap{
            { QStringLiteral("uuid"), it.value().value(Bluez::GattServiceInterface).value(PropertyUuid).toString() },
            { QStringLiteral("chars"), characteristics },
        });
    }
    return services;
}

QVariantMap BlueTransport::properties(const QString &path, const QString &interface) const
{
    return m_objects.value(path).value(interface);
}

QDBusMessage BlueTransport::methodCall(const QString &path, const QString &interface, const QString &method) const
{
    return QDBusMessage::createMethodCall(Bluez::Service, path, interface, method);
}
