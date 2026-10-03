#ifndef BLUETRANSPORT_H
#define BLUETRANSPORT_H

#include <QDBusConnection>
#include <QHash>
#include <QMap>
#include <QObject>
#include <QString>
#include <QStringList>
#include <QVariant>

#include "bluez.h"

class IndoorBikeCore;
class NotifyPipe;
class QDBusMessage;
class QDBusPendingCallWatcher;
class QDBusServiceWatcher;

// The radio the shared Kotlin drives, on BlueZ. The managers in shared/ (trainer, heart rate,
// controller) ask for scans, connections, subscriptions, writes and reads; core/'s BridgedCentral
// turns those into `ble.*` events, which arrive here, and this answers with `ble.*` commands. See
// BridgedCentral.kt for the list.
//
// Everything here runs on the main thread. BlueZ's object tree is mirrored once with
// GetManagedObjects and kept current from InterfacesAdded, InterfacesRemoved and one path-less
// PropertiesChanged subscription, as the Companion for UNA does.
//
// Lessons from that app, measured on a Jolla C2, which this follows:
//  - A device is found by its Address property, never by a path built from the address: one that
//    bonded at a private address keeps a path that names a different one.
//  - Notifications come through AcquireNotify's socket where the characteristic allows it: D-Bus
//    merges notifications that arrive close together.
//  - Sockets are closed before a disconnect, never after: BlueZ otherwise keeps the notifications
//    claimed with nobody behind them, and refuses them to every later start until bluetoothd
//    restarts.
//  - An app cannot pair. BlueZ refuses Pair and agent registration outside the privileged group,
//    so a device that needs a bond is paired in Settings > Bluetooth first. Trainers and straps
//    usually need none.
class BlueTransport : public QObject
{
    Q_OBJECT

public:
    explicit BlueTransport(IndoorBikeCore *core, QObject *parent = nullptr);
    ~BlueTransport() override;

    // BlueZ's Flags strings as the GATT properties bit field the shared code reads.
    static int propertyBits(const QStringList &flags);

private slots:
    void onEvent(const QString &topic, const QVariant &payload);
    void onObjectsLoaded(QDBusPendingCallWatcher *watcher);
    void onInterfacesAdded(const QDBusObjectPath &path, const InterfaceList &interfaces);
    void onInterfacesRemoved(const QDBusObjectPath &path, const QStringList &interfaces);
    void onPropertiesChanged(const QString &interface, const QVariantMap &changed, const QStringList &invalidated,
                             const QDBusMessage &message);
    void onDeviceDisconnected(const QString &reason, const QString &message, const QDBusMessage &signal);
    void onServiceRegistered();
    void onServiceUnregistered();

private:
    struct Link {
        QString path;
        QString state;
        QString lastError;
    };
    struct Subscription {
        QString address;
        QString uuid;
        NotifyPipe *pipe = nullptr;
        bool overDBus = false;
    };

    void loadObjects();
    void setDiscovery(bool on, const QStringList &uuids);
    void startDiscovery();
    void connectDevice(const QString &address);
    void disconnectDevice(const QString &address);
    void subscribe(const QVariantMap &args, bool on);
    void startNotifyOverDBus(const QString &path);
    void write(const QVariantMap &args);
    void read(const QVariantMap &args);

    void deviceChanged(const QString &path, const QVariantMap &changed);
    void reportAdapter();
    void reportAdvert(const QString &path);
    void reportLink(const QString &address, const QString &state, const QString &error = QString());
    void reportValue(const Subscription &subscription, const QByteArray &value);
    void subscribeFailed(const QString &address, const QString &uuid, const QString &message);
    void done(const QString &token, bool ok, const QByteArray &value = QByteArray(),
              const QString &message = QString());
    void releaseSubscriptions(const QString &address);

    QString devicePathFor(const QString &address) const;
    QString addressOfPath(const QString &path) const;
    QString characteristicPath(const QString &devicePath, const QString &service, const QString &uuid) const;
    QVariantList servicesOf(const QString &devicePath) const;
    QVariantMap properties(const QString &path, const QString &interface) const;
    QDBusMessage methodCall(const QString &path, const QString &interface, const QString &method) const;

    IndoorBikeCore *m_core;
    QDBusConnection m_bus;
    QDBusServiceWatcher *m_serviceWatcher;
    QMap<QString, InterfaceList> m_objects;
    QString m_adapterPath;
    bool m_loaded = false;
    QString m_reportedAdapter;

    bool m_wantDiscovery = false;
    QStringList m_discoveryUuids;
    bool m_discovering = false;

    // The connections the core asked for, by address.
    QHash<QString, Link> m_links;
    // Notifications switched on, by characteristic path.
    QHash<QString, Subscription> m_subscriptions;
};

#endif // BLUETRANSPORT_H
